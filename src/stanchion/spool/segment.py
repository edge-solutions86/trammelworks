"""Segment files: what the spool is actually made of.

The spool is a directory of append only files. One is open for writing at a
time and the rest are sealed. A file is sealed when it reaches the configured
size, and only sealed files are shipped or evicted, which removes a whole class
of problem: nothing ever reads a file that is being written to.

The name carries what a reader needs before opening anything::

    000000000041-payment.spool

That is the sequence number, zero padded so a lexical sort is a numeric sort,
and the priority class. Putting the class in the name means an eviction sweep
can decide what to delete from a directory listing, without opening a single
file, which matters when the reason for the sweep is that the disk is full.

Sequence numbers never restart. A restart reads the highest number on disk and
carries on from there, because reusing a number would make an acknowledgement
for the old file look like an acknowledgement for the new one.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from pathlib import Path

from stanchion.errors import SpoolError

SUFFIX = ".spool"
PARTIAL_SUFFIX = ".part"
SEQUENCE_DIGITS = 12

NAME_PATTERN = re.compile(r"^(?P<sequence>\d{12})-(?P<klass>[a-z][a-z0-9_]*)\.spool$")
CLASS_PATTERN = re.compile(r"^[a-z][a-z0-9_]*$")

DEFAULT_SEGMENT_BYTES = 8 * 1024 * 1024


def check_class(name: str) -> str:
    if not CLASS_PATTERN.match(name):
        raise SpoolError("malformed priority class", detail=name)
    return name


def segment_name(sequence: int, klass: str) -> str:
    if sequence < 0:
        raise SpoolError("negative segment sequence", detail=str(sequence))
    return f"{sequence:0{SEQUENCE_DIGITS}d}-{check_class(klass)}{SUFFIX}"


@dataclass(frozen=True, slots=True)
class SegmentInfo:
    """What a sealed segment's name and stat tell us."""

    path: Path
    sequence: int
    klass: str
    size: int

    @property
    def name(self) -> str:
        return self.path.name


def parse_segment_name(name: str) -> tuple[int, str] | None:
    """The sequence and class in a segment file name, or None if it is not one."""
    match = NAME_PATTERN.match(name)
    if match is None:
        return None
    return int(match.group("sequence")), match.group("klass")


def list_segments(directory: Path) -> list[SegmentInfo]:
    """Every sealed segment, oldest first."""
    if not directory.exists():
        return []
    found: list[SegmentInfo] = []
    for path in sorted(directory.iterdir()):
        parsed = parse_segment_name(path.name)
        if parsed is None or not path.is_file():
            continue
        sequence, klass = parsed
        found.append(
            SegmentInfo(path=path, sequence=sequence, klass=klass, size=path.stat().st_size)
        )
    return found


def next_sequence(directory: Path) -> int:
    """One past the highest sequence on disk, counting partials."""
    highest = -1
    if not directory.exists():
        return 0
    for path in directory.iterdir():
        name = path.name.removesuffix(PARTIAL_SUFFIX)
        parsed = parse_segment_name(name)
        if parsed is not None:
            highest = max(highest, parsed[0])
    return highest + 1


@dataclass(slots=True)
class SegmentWriter:
    """The one segment currently open for writing.

    Writes go to a ``.part`` file and the seal is a rename. A reader listing the
    directory therefore never sees a segment that is still growing, and it does
    not have to take a lock to be sure of that.
    """

    directory: Path
    sequence: int
    klass: str
    max_bytes: int = DEFAULT_SEGMENT_BYTES
    sync_every: int = 0
    size: int = 0
    frames: int = 0
    _handle: object | None = field(default=None, init=False, repr=False)
    _since_sync: int = field(default=0, init=False)

    def __post_init__(self) -> None:
        check_class(self.klass)
        if self.max_bytes <= 0:
            raise SpoolError("segment max_bytes must be positive", detail=str(self.max_bytes))
        if self.sync_every < 0:
            raise SpoolError("sync_every cannot be negative", detail=str(self.sync_every))

    @property
    def partial_path(self) -> Path:
        return self.directory / (segment_name(self.sequence, self.klass) + PARTIAL_SUFFIX)

    @property
    def sealed_path(self) -> Path:
        return self.directory / segment_name(self.sequence, self.klass)

    @property
    def open(self) -> bool:
        return self._handle is not None

    def _stream(self) -> object:
        if self._handle is None:
            self.directory.mkdir(parents=True, exist_ok=True)
            handle = self.partial_path.open("ab")
            self._handle = handle
            self.size = self.partial_path.stat().st_size
        return self._handle

    def append(self, frame: bytes) -> int:
        """Write one framed record. Returns the offset it landed at."""
        handle = self._stream()
        offset = self.size
        handle.write(frame)  # type: ignore[attr-defined]
        self.size += len(frame)
        self.frames += 1
        self._since_sync += 1
        if self.sync_every and self._since_sync >= self.sync_every:
            self.flush()
        return offset

    def flush(self) -> None:
        """Push to the filesystem and ask it to commit."""
        if self._handle is None:
            return
        self._handle.flush()  # type: ignore[attr-defined]
        os.fsync(self._handle.fileno())  # type: ignore[attr-defined]
        self._since_sync = 0

    @property
    def full(self) -> bool:
        return self.size >= self.max_bytes

    def would_overflow(self, frame_size: int) -> bool:
        """True when this frame would take the segment past its size."""
        return self.size + frame_size > self.max_bytes

    def seal(self) -> SegmentInfo | None:
        """Close and rename. An empty segment is removed rather than sealed."""
        if self._handle is not None:
            self.flush()
            self._handle.close()  # type: ignore[attr-defined]
            self._handle = None
        if not self.partial_path.exists():
            return None
        if self.size == 0:
            self.partial_path.unlink()
            return None
        os.replace(self.partial_path, self.sealed_path)
        return SegmentInfo(
            path=self.sealed_path, sequence=self.sequence, klass=self.klass, size=self.size
        )
