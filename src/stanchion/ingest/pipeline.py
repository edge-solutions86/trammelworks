"""One file, followed end to end.

Tail the file, turn bytes into fragments, join the chunks the runtime split, then
optionally stitch the lines the application meant as one event. The order is
fixed and it matters: stitching before joining would look at half a line and
decide it starts an event.

Rotation is the interesting case. When the file the tailer was reading is
replaced, everything held in the joiner and the stitcher belongs to the old file
and nothing more is ever coming for it. That is exactly when flushing is right,
and it is the only time other than shutdown, so the pipeline does it here rather
than leaving each component to guess.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from stanchion.clocks import Clock, SystemClock
from stanchion.identity import parse_unit
from stanchion.ingest.checkpoint import Checkpoint, CheckpointStore
from stanchion.ingest.join import Joiner, JoinLimits
from stanchion.ingest.multiline import Stitcher
from stanchion.ingest.readers import FragmentReader
from stanchion.ingest.tail import Tailer, TailEvent
from stanchion.paths import LogPathInfo
from stanchion.records import LogRecord

FLUSHING_EVENTS = frozenset({TailEvent.ROTATED, TailEvent.TRUNCATED, TailEvent.MISSING})


@dataclass(slots=True)
class PipelineStats:
    """Per file numbers, reported per unit in the nightly summary."""

    polls: int = 0
    fragments: int = 0
    records: int = 0
    skipped: int = 0
    rotations: int = 0
    truncations: int = 0
    disappearances: int = 0
    lost_bytes: int = 0


@dataclass(slots=True)
class UnitPipeline:
    """Follows one container log file and produces finished records."""

    site: str
    info: LogPathInfo
    reader: FragmentReader
    limits: JoinLimits = field(default_factory=JoinLimits)
    stitcher: Stitcher | None = None
    checkpoints: CheckpointStore | None = None
    clock: Clock = field(default_factory=SystemClock)
    tailer: Tailer = field(init=False)
    joiner: Joiner = field(init=False)
    stats: PipelineStats = field(default_factory=PipelineStats)

    def __post_init__(self) -> None:
        self.tailer = Tailer(path=Path(self.info.path), reader=self.reader)
        self.joiner = Joiner(site=self.site, unit=self.unit, limits=self.limits)

    @property
    def unit(self) -> str:
        return parse_unit(self.info.unit)

    @property
    def key(self) -> str:
        """The checkpoint key. One per followed file, not one per unit."""
        return f"{self.site}_{self.unit}_{self.info.attempt or 0}"

    def resume(self) -> bool:
        """Seek to the stored offset if it still describes this file."""
        if self.checkpoints is None:
            return False
        stored = self.checkpoints.load(self.key)
        if stored is None or stored.path != self.info.path:
            return False
        try:
            identity = self.tailer.path.stat()
        except FileNotFoundError:
            return False
        if stored.device != identity.st_dev or stored.inode != identity.st_ino:
            return False
        if stored.offset > identity.st_size:
            return False
        self.tailer.seek_to(stored.offset)
        return True

    def poll(self) -> list[LogRecord]:
        """One pass. Returns whatever finished during it."""
        self.stats.polls += 1
        result = self.tailer.poll()
        out: list[LogRecord] = []
        if result.event in FLUSHING_EVENTS:
            out.extend(self._flush())
            self._count_event(result.event)
            self.stats.lost_bytes += result.lost_bytes
        self.stats.fragments += len(result.fragments)
        self.stats.skipped += result.skipped
        for fragment in result.fragments:
            joined = self.joiner.feed(fragment)
            if joined is not None:
                out.extend(self._stitch(joined))
        self.stats.records += len(out)
        self._checkpoint()
        return out

    def close(self) -> list[LogRecord]:
        """Shutdown. Everything held is emitted, marked for what it is."""
        out = self._flush()
        self.stats.records += len(out)
        self._checkpoint()
        return out

    def _flush(self) -> list[LogRecord]:
        out: list[LogRecord] = []
        for record in self.joiner.flush():
            out.extend(self._stitch(record))
        if self.stitcher is not None:
            out.extend(self.stitcher.flush())
        return out

    def _stitch(self, record: LogRecord) -> list[LogRecord]:
        if self.stitcher is None:
            return [record]
        return self.stitcher.feed(record)

    def _count_event(self, event: TailEvent) -> None:
        if event is TailEvent.ROTATED:
            self.stats.rotations += 1
        elif event is TailEvent.TRUNCATED:
            self.stats.truncations += 1
        elif event is TailEvent.MISSING:
            self.stats.disappearances += 1

    def _checkpoint(self) -> None:
        """Store the position that has been *parsed*, not the one read.

        The tailer holds back bytes that do not form a complete line yet.
        Checkpointing the read position would skip that tail on the next start.
        """
        if self.checkpoints is None or self.tailer.identity is None:
            return
        self.checkpoints.save(
            Checkpoint(
                key=self.key,
                path=self.info.path,
                device=self.tailer.identity.device,
                inode=self.tailer.identity.inode,
                offset=self.tailer.offset - self.tailer.buffered,
                updated_at=self.clock.now(),
            )
        )
