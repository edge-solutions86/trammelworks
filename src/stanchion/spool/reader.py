"""Reading segments back, and picking up after a power cut.

Two jobs live here. The ordinary one is reading a sealed segment into records so
the shipper can send them. The other one runs once at startup and matters more.

A ``.part`` file is a segment that was being written when the process stopped.
That is not damage, it is the normal state of the file the box was writing when
the forecourt lost power, and it contains real records that nothing else has.
Deleting it loses a shift. Sealing it as it stands hands the shipper a file with
a half written frame on the end.

So recovery walks the frames, finds where the good ones stop, truncates there
and seals what is left. The bytes after that point are gone either way; the
question is only whether the ones before them go with them.

A sealed segment that fails to read is a different thing. Sealed means it was
whole once, so a checksum failure there is media rot or a bug rather than an
interrupted write. It is quarantined rather than truncated, because deleting the
evidence of a disk going bad is not helpful to whoever gets called out.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from stanchion.errors import SpoolError
from stanchion.spool.frame import Frame, iter_frames
from stanchion.spool.segment import (
    PARTIAL_SUFFIX,
    SegmentInfo,
    parse_segment_name,
)

QUARANTINE_SUFFIX = ".bad"


@dataclass(slots=True)
class SegmentRead:
    """What came out of one segment."""

    path: Path
    frames: list[Frame] = field(default_factory=list)
    consumed: int = 0
    size: int = 0
    note: str | None = None

    @property
    def whole(self) -> bool:
        return self.note is None and self.consumed == self.size

    @property
    def payloads(self) -> list[dict[str, Any]]:
        return [frame.payload for frame in self.frames]


def read_segment(path: Path) -> SegmentRead:
    """Read every whole frame in a segment file."""
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise SpoolError("segment unreadable", detail=str(exc)) from exc
    frames, consumed, note = iter_frames(data)
    return SegmentRead(path=path, frames=frames, consumed=consumed, size=len(data), note=note)


@dataclass(frozen=True, slots=True)
class Recovery:
    """What startup had to do to one interrupted segment."""

    path: Path
    sealed: SegmentInfo | None
    frames: int
    dropped_bytes: int
    removed: bool = False


def recover_partial(path: Path) -> Recovery:
    """Truncate an interrupted segment to its last good frame and seal it."""
    if not path.name.endswith(PARTIAL_SUFFIX):
        raise SpoolError("not a partial segment", detail=path.name)
    sealed_name = path.name.removesuffix(PARTIAL_SUFFIX)
    parsed = parse_segment_name(sealed_name)
    if parsed is None:
        raise SpoolError("partial segment has an unreadable name", detail=path.name)
    found = read_segment(path)
    dropped = found.size - found.consumed
    if found.consumed == 0:
        path.unlink()
        return Recovery(path=path, sealed=None, frames=0, dropped_bytes=dropped, removed=True)
    if dropped:
        with path.open("r+b") as handle:
            handle.truncate(found.consumed)
            handle.flush()
            os.fsync(handle.fileno())
    target = path.with_name(sealed_name)
    os.replace(path, target)
    sequence, klass = parsed
    return Recovery(
        path=path,
        sealed=SegmentInfo(path=target, sequence=sequence, klass=klass, size=found.consumed),
        frames=len(found.frames),
        dropped_bytes=dropped,
    )


def recover_directory(directory: Path) -> list[Recovery]:
    """Deal with every interrupted segment, oldest first."""
    if not directory.exists():
        return []
    out: list[Recovery] = []
    for path in sorted(directory.iterdir()):
        if path.is_file() and path.name.endswith(PARTIAL_SUFFIX):
            out.append(recover_partial(path))
    return out


def quarantine(path: Path) -> Path:
    """Move a sealed segment that will not read out of the way.

    Kept rather than deleted. A segment that was whole and is not any more is
    evidence about the disk, and the next thing that happens on that box is
    somebody asking what else it has lost.
    """
    target = path.with_name(path.name + QUARANTINE_SUFFIX)
    if target.exists():
        target.unlink()
    os.replace(path, target)
    return target
