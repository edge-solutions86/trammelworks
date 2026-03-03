"""Getting the acknowledged prefix of a segment off the disk.

A segment is deleted whole once every record in it has been acknowledged. Until
then it holds its full size, and on a site that is shipping slowly that is the
common case rather than the corner: eight megabytes of segment of which seven
have been delivered and one has not, sitting against a budget that is already
tight enough to be evicting the chatter class.

Compaction rewrites such a segment without its acknowledged prefix. It does not
re-encode anything. The remaining frames are copied byte for byte, so their
checksums and their compression flags come through untouched and a compacted
segment is indistinguishable from one that was written short in the first place.
Re-encoding would mean every compaction pass is another chance to write a record
back out wrong, and there is no reason to take it.

The sequence number stays. That is a deliberate exception to the rule that a
number is never reused, and it holds because the file keeps its identity: same
class, same position in the ship order, same name in the ledger. What changes is
that it is shorter. Giving it a fresh number would move it to the back of the
queue, which is the opposite of what the operator wants from the oldest
unshipped payment records on the box.

The crash window is the gap between the rename and the ledger write. Land there
and the ledger says twelve of forty for a file that now holds twenty eight. The
shipper reads that as a compaction that landed and sends the twenty eight again,
which is the at least once behaviour the whole delivery path is built on. The
other direction, a segment that has grown under a watermark, is refused: a
sealed segment cannot grow, so that reading is corruption or a reused number,
and neither should be shipped past quietly.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from stanchion.errors import SpoolError
from stanchion.eventlog import EventLog, null_log
from stanchion.ship.ack import AckLedger
from stanchion.spool.frame import iter_frames
from stanchion.spool.reader import read_segment
from stanchion.spool.segment import SegmentInfo
from stanchion.telemetry import Registry

COMPACT_SUFFIX = ".compact"

#: Below this there is nothing worth the rewrite, whatever the fraction says.
DEFAULT_MIN_RECLAIM = 64 * 1024

#: And below this fraction the same is true, whatever the byte count says.
DEFAULT_MIN_FRACTION = 0.25


@dataclass(frozen=True, slots=True)
class CompactionPlan:
    """One segment worth rewriting, and what it would give back."""

    segment: SegmentInfo
    drop: int
    keep: int
    reclaim: int

    @property
    def name(self) -> str:
        return self.segment.name

    @property
    def fraction(self) -> float:
        return self.reclaim / self.segment.size if self.segment.size else 0.0

    def __str__(self) -> str:
        return f"{self.name} drop {self.drop} keep {self.keep} frees {self.reclaim} bytes"


def prefix_bytes(data: bytes, count: int) -> int:
    """How many bytes the first ``count`` frames occupy.

    Walks the frames rather than trusting a stored offset, because the only
    thing that can say where frame twelve ends is frame eleven's length, and
    that is on disk beside it.
    """
    if count < 0:
        raise SpoolError("cannot measure a negative number of frames", detail=str(count))
    frames, consumed, note = iter_frames(data)
    if note is not None:
        raise SpoolError("segment will not read whole", detail=note)
    if count > len(frames):
        raise SpoolError(
            "asked for more frames than the segment holds", detail=f"{count} of {len(frames)}"
        )
    if count == len(frames):
        return consumed
    return sum(frame.stored_size for frame in frames[:count])


def plan_one(
    segment: SegmentInfo,
    acked: int,
    total: int,
    min_reclaim: int = DEFAULT_MIN_RECLAIM,
    min_fraction: float = DEFAULT_MIN_FRACTION,
) -> CompactionPlan | None:
    """Decide whether one segment is worth rewriting."""
    if acked <= 0 or acked >= total:
        # Nothing acknowledged, or everything: the second one is the shipper's
        # to delete outright and compacting it to empty would only get in the
        # way of that.
        return None
    try:
        data = segment.path.read_bytes()
    except OSError as exc:
        raise SpoolError("segment unreadable", detail=str(exc)) from exc
    reclaim = prefix_bytes(data, acked)
    if reclaim < min_reclaim and reclaim < min_fraction * len(data):
        return None
    return CompactionPlan(segment=segment, drop=acked, keep=total - acked, reclaim=reclaim)


@dataclass(frozen=True, slots=True)
class Survey:
    """What a look over the directory found."""

    plans: tuple[CompactionPlan, ...] = ()
    unreadable: tuple[str, ...] = ()

    @property
    def reclaim(self) -> int:
        return sum(plan.reclaim for plan in self.plans)


def plan_compaction(
    segments: list[SegmentInfo],
    ledger: AckLedger,
    min_reclaim: int = DEFAULT_MIN_RECLAIM,
    min_fraction: float = DEFAULT_MIN_FRACTION,
    pinned: frozenset[Path] = frozenset(),
) -> Survey:
    """Every segment worth rewriting, biggest saving first.

    A segment that will not read is reported rather than raised. Compaction is
    a housekeeping pass over every segment on the box, and one bad file in the
    middle of it must not stop the other nine from being rewritten. Deciding
    what to do about the bad one belongs to the shipper, which quarantines it.
    """
    plans: list[CompactionPlan] = []
    unreadable: list[str] = []
    for segment in segments:
        if segment.path in pinned:
            continue
        mark = ledger.marks.get(segment.name)
        if mark is None:
            continue
        try:
            found = plan_one(
                segment,
                mark.acked,
                mark.total,
                min_reclaim=min_reclaim,
                min_fraction=min_fraction,
            )
        except SpoolError:
            unreadable.append(segment.name)
            continue
        if found is not None:
            plans.append(found)
    plans.sort(key=lambda item: (-item.reclaim, item.segment.sequence))
    return Survey(plans=tuple(plans), unreadable=tuple(unreadable))


def rewrite(path: Path, drop: int) -> int:
    """Rewrite a segment without its first ``drop`` frames. Returns the new size.

    The new bytes go to a sidecar and land with a rename, so a reader that walks
    the directory during this sees either the whole old file or the whole new
    one. There is no moment at which the segment is half of each.
    """
    found = read_segment(path)
    if found.note is not None:
        raise SpoolError("will not compact a segment that does not read", detail=found.note)
    data = path.read_bytes()
    cut = prefix_bytes(data, drop)
    tail = data[cut : found.consumed]
    sidecar = path.with_name(path.name + COMPACT_SUFFIX)
    with sidecar.open("wb") as handle:
        handle.write(tail)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(sidecar, path)
    return len(tail)


def clear_sidecars(directory: Path) -> list[Path]:
    """Remove any half written compaction a crash left behind.

    The sidecar is never the live file, so one found on disk is by definition
    incomplete work and the original it was derived from is still there.
    """
    if not directory.exists():
        return []
    removed: list[Path] = []
    for path in sorted(directory.iterdir()):
        if path.is_file() and path.name.endswith(COMPACT_SUFFIX):
            path.unlink()
            removed.append(path)
    return removed


@dataclass(frozen=True, slots=True)
class CompactionResult:
    """What one compaction pass did."""

    segments: int = 0
    frames_dropped: int = 0
    reclaimed: int = 0
    failed: tuple[str, ...] = ()

    @property
    def idle(self) -> bool:
        return self.segments == 0 and not self.failed

    def __str__(self) -> str:
        end = f", {len(self.failed)} failed" if self.failed else ""
        return (
            f"compacted {self.segments} segments, "
            f"dropped {self.frames_dropped} records, "
            f"freed {self.reclaimed} bytes{end}"
        )


@dataclass(slots=True)
class Compactor:
    """Rewrites part shipped segments, and keeps the ledger in step."""

    directory: Path
    ledger: AckLedger
    min_reclaim: int = DEFAULT_MIN_RECLAIM
    min_fraction: float = DEFAULT_MIN_FRACTION
    events: EventLog = field(default_factory=null_log)
    metrics: Registry = field(default_factory=Registry)

    def run(
        self, segments: list[SegmentInfo], pinned: frozenset[Path] = frozenset()
    ) -> CompactionResult:
        """Compact everything worth compacting, in order of what it gives back."""
        survey = plan_compaction(
            segments,
            self.ledger,
            min_reclaim=self.min_reclaim,
            min_fraction=self.min_fraction,
            pinned=pinned,
        )
        done = 0
        dropped = 0
        freed = 0
        failed: list[str] = list(survey.unreadable)
        for name in survey.unreadable:
            self.metrics.incr("spool_compaction_failures")
            self.events.warn("compaction_skipped", segment=name, reason="will not read")
        for plan in survey.plans:
            try:
                size = rewrite(plan.segment.path, plan.drop)
            except (SpoolError, OSError) as exc:
                failed.append(plan.name)
                self.metrics.incr("spool_compaction_failures")
                self.events.warn("compaction_failed", segment=plan.name, reason=str(exc))
                continue
            self.ledger.resize(plan.name, plan.keep)
            done += 1
            dropped += plan.drop
            freed += plan.segment.size - size
            self.metrics.incr("spool_compacted_segments")
            self.metrics.incr("spool_compacted_bytes", plan.segment.size - size)
            self.events.info(
                "segment_compacted",
                segment=plan.name,
                dropped=plan.drop,
                kept=plan.keep,
                freed=plan.segment.size - size,
            )
        return CompactionResult(
            segments=done,
            frames_dropped=dropped,
            reclaimed=freed,
            failed=tuple(failed),
        )
