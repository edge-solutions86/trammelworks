"""The spool itself: the piece the rest of the box talks to.

One open segment per priority class, not one overall. That follows from
eviction: a segment is deleted whole, so a segment holding both payment records
and price sign chatter could not be deleted without taking the payment records
with it. Splitting on write is what makes the class boundary mean anything on
disk.

Writing a record is four steps and the order is what keeps the budget honest:

1. frame it, because the framed size is what costs disk, not the message length
2. make room if that size does not fit
3. roll the class's segment if the frame would take it past its size
4. append

Step 2 can fail. When it does the write is refused rather than accepted into a
spool that has already overrun, and the caller learns that immediately. A
forecourt that has lost its uplink for three days is exactly the situation this
exists for, and the honest outcome there is that the lowest class stops being
kept, not that the disk fills and the tills stop.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from stanchion.errors import SpoolError
from stanchion.eventlog import EventLog, null_log
from stanchion.records import LogRecord
from stanchion.spool.budget import Budget, Usage, measure
from stanchion.spool.evict import EvictionResult, apply_eviction, plan_eviction
from stanchion.spool.frame import encode_frame
from stanchion.spool.priority import PriorityTable
from stanchion.spool.reader import Recovery, recover_directory
from stanchion.spool.segment import (
    DEFAULT_SEGMENT_BYTES,
    SegmentInfo,
    SegmentWriter,
    list_segments,
    next_sequence,
)
from stanchion.telemetry import Registry


@dataclass(frozen=True, slots=True)
class Receipt:
    """Where a record landed."""

    klass: str
    sequence: int
    offset: int
    size: int


@dataclass(slots=True)
class Spool:
    """A bounded, class partitioned, append only store of records."""

    directory: Path
    budget: Budget
    table: PriorityTable = field(default_factory=PriorityTable)
    segment_bytes: int = DEFAULT_SEGMENT_BYTES
    sync_every: int = 0
    compress: bool = True
    events: EventLog = field(default_factory=null_log)
    metrics: Registry = field(default_factory=Registry)
    writers: dict[str, SegmentWriter] = field(default_factory=dict, init=False)
    pinned: set[Path] = field(default_factory=set, init=False)

    def __post_init__(self) -> None:
        self.directory = Path(self.directory)
        if self.segment_bytes > self.budget.usable_bytes:
            raise SpoolError(
                "a segment cannot be larger than the usable budget",
                detail=f"{self.segment_bytes} > {self.budget.usable_bytes}",
            )

    def open(self) -> list[Recovery]:
        """Deal with anything an interrupted run left behind."""
        found = recover_directory(self.directory)
        for item in found:
            self.metrics.incr("spool_recovered_segments")
            if item.dropped_bytes:
                self.metrics.incr("spool_recovered_lost_bytes", item.dropped_bytes)
            self.events.warn(
                "segment_recovered",
                path=str(item.path),
                frames=item.frames,
                dropped=item.dropped_bytes,
                removed=item.removed,
            )
        return found

    def segments(self) -> list[SegmentInfo]:
        return list_segments(self.directory)

    def usage(self) -> Usage:
        open_bytes = sum(writer.size for writer in self.writers.values())
        return measure(self.segments(), open_bytes=open_bytes)

    def append(self, record: LogRecord) -> Receipt:
        """Frame a record and write it into its class's segment."""
        klass = self.table.class_for(record.unit)
        frame = encode_frame(record.as_dict(), compress=self.compress)
        self._make_room(len(frame))
        writer = self._writer_for(klass.name)
        if writer.would_overflow(len(frame)):
            self._seal(klass.name)
            writer = self._writer_for(klass.name)
        offset = writer.append(frame)
        self.metrics.incr("spool_records")
        self.metrics.incr("spool_bytes", len(frame))
        return Receipt(klass=klass.name, sequence=writer.sequence, offset=offset, size=len(frame))

    def append_all(self, records: list[LogRecord]) -> list[Receipt]:
        return [self.append(record) for record in records]

    def flush(self) -> None:
        for writer in self.writers.values():
            writer.flush()

    def seal_all(self) -> list[SegmentInfo]:
        """Seal every open segment, which is what shutdown does."""
        sealed: list[SegmentInfo] = []
        for klass in list(self.writers):
            found = self._seal(klass)
            if found is not None:
                sealed.append(found)
        return sealed

    def detach(self) -> None:
        """Release every open segment without sealing it.

        What is left behind is exactly what a power cut leaves, so the next
        start goes through recovery. The alternative, sealing, would hide from
        the recovery path that it is ever needed.
        """
        for writer in self.writers.values():
            writer.close()
        self.writers.clear()

    def pin(self, path: Path) -> None:
        """Protect a segment from eviction while it is being shipped."""
        self.pinned.add(path)

    def unpin(self, path: Path) -> None:
        self.pinned.discard(path)

    def _writer_for(self, klass: str) -> SegmentWriter:
        found = self.writers.get(klass)
        if found is None:
            found = SegmentWriter(
                directory=self.directory,
                sequence=next_sequence(self.directory),
                klass=klass,
                max_bytes=self.segment_bytes,
                sync_every=self.sync_every,
            )
            self.writers[klass] = found
        return found

    def _seal(self, klass: str) -> SegmentInfo | None:
        writer = self.writers.pop(klass, None)
        if writer is None:
            return None
        sealed = writer.seal()
        if sealed is not None:
            self.metrics.incr("spool_segments_sealed")
            self.events.info("segment_sealed", path=str(sealed.path), size=sealed.size, klass=klass)
        return sealed

    def _make_room(self, size: int) -> EvictionResult | None:
        """Free space if this write does not fit, or refuse it."""
        usage = self.usage()
        if self.budget.admits(usage, size):
            return None
        # Sealing first turns open bytes into deletable ones and is the reason
        # the reserve exists: this write happens while the budget is already up
        # against its usable limit.
        self.seal_all()
        usage = self.usage()
        needed = self.budget.over_by(usage) + size
        plan = plan_eviction(self.segments(), self.table, needed, frozenset(self.pinned))
        result = apply_eviction(plan)
        self.metrics.incr("spool_evicted_segments", result.count)
        self.metrics.incr("spool_evicted_bytes", result.freed)
        if result.count:
            self.events.warn("spool_evicted", segments=result.count, bytes=result.freed)
        if not self.budget.admits(self.usage(), size):
            raise SpoolError(
                "spool is full and nothing more can be evicted",
                detail=f"needed {needed}, freed {result.freed}",
            )
        return result
