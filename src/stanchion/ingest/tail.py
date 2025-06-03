"""Following a log file that the runtime keeps moving out from under us.

Three things happen to a container log file and they need different answers:

* **it grows.** The ordinary case. Read from the last offset, keep whatever
  trailing bytes did not form a complete line, carry on.
* **it is rotated by rename.** The runtime creates a new file at the same path.
  The old one still exists under ``.1`` and still has bytes we never read. The
  identity changes, so that is how it is noticed.
* **it is rotated in place.** logrotate with ``copytruncate`` copies the file and
  then truncates the original to zero. The identity does *not* change and the
  size goes backwards, so a reader watching only the identity will happily seek
  to an offset past the end and then read nothing forever.

Both rotations lose the bytes between the last read and the rotation. That loss
is reported rather than hidden, because on a metered link the operator's next
question is always whether a gap was ours or the application's.

File identity is device plus inode. That is a weaker guarantee than it looks:
inodes are reused, so a fast rotate, delete and create can present the same pair
for a different file.

Size is not enough either, and that one bit us. A ``copytruncate`` rotation
followed by a busy container can push the new file past the old offset before the
next poll, and then the size test says "grew" and the reader carries on from an
offset that now points into the middle of a different shift. So the opening bytes
of the file are kept as a signature.

The signature is a *prefix*, not a fixed window, and the test is that the file
still starts with it. A file that has only grown still starts with what it
started with. A file that was rewritten does not, unless the new content repeats
the old opening bytes, and a CRI line opens with a timestamp.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path

from stanchion.errors import IngestError, ParseError
from stanchion.ingest.readers import FragmentReader
from stanchion.records import Fragment

DEFAULT_CHUNK = 64 * 1024

#: How much of the head of the file to remember. One CRI line is about sixty
#: bytes, so this covers a whole timestamp, which is the part that changes.
SIGNATURE_BYTES = 64


class TailEvent(StrEnum):
    """What happened to the file between two polls."""

    NONE = "none"
    GROWN = "grown"
    ROTATED = "rotated"
    TRUNCATED = "truncated"
    MISSING = "missing"


@dataclass(frozen=True, slots=True)
class FileIdentity:
    """Enough of a stat result to tell one file from another."""

    device: int
    inode: int

    @classmethod
    def of(cls, path: Path) -> FileIdentity:
        info = path.stat()
        return cls(device=info.st_dev, inode=info.st_ino)


@dataclass(slots=True)
class PollResult:
    """What one poll produced."""

    fragments: list[Fragment] = field(default_factory=list)
    event: TailEvent = TailEvent.NONE
    bytes_read: int = 0
    skipped: int = 0
    lost_bytes: int = 0
    errors: list[ParseError] = field(default_factory=list)

    @property
    def empty(self) -> bool:
        return not self.fragments and self.event is TailEvent.NONE


@dataclass(slots=True)
class Tailer:
    """Follows one file with one reader."""

    path: Path
    reader: FragmentReader
    chunk_size: int = DEFAULT_CHUNK
    offset: int = 0
    identity: FileIdentity | None = None
    pending: bytes = b""
    signature: bytes = b""

    def __post_init__(self) -> None:
        if self.chunk_size <= 0:
            raise IngestError("chunk_size must be positive", detail=str(self.chunk_size))

    def poll(self) -> PollResult:
        """Read whatever is new, handling rotation and truncation."""
        result = PollResult()
        try:
            info = self.path.stat()
        except FileNotFoundError:
            if self.identity is not None:
                result.event = TailEvent.MISSING
                result.lost_bytes = len(self.pending)
                self._reset()
            return result
        found = FileIdentity(device=info.st_dev, inode=info.st_ino)
        head = self._head() if self.signature else b""
        if self.identity is None:
            self.identity = found
        elif found != self.identity:
            self._restart(result, TailEvent.ROTATED, found)
        elif info.st_size < self.offset:
            self._restart(result, TailEvent.TRUNCATED, found)
        elif self.signature and not head.startswith(self.signature):
            # Same inode, same or larger size, different bytes at the front.
            # copytruncate, and the container refilled it before we looked.
            self._restart(result, TailEvent.TRUNCATED, found)
        if info.st_size <= self.offset:
            return result
        chunk = self._read_chunk()
        if not chunk:
            return result
        result.bytes_read = len(chunk)
        if result.event is TailEvent.NONE:
            result.event = TailEvent.GROWN
        buffer = self.pending + chunk
        base = self.offset - len(self.pending)
        parsed = self.reader.read(buffer, path=self.path.as_posix(), base_offset=base)
        result.fragments = parsed.fragments
        result.skipped = parsed.skipped
        result.errors = parsed.errors
        self.offset += len(chunk)
        self.pending = buffer[parsed.consumed :]
        self._remember_head()
        return result

    def _restart(self, result: PollResult, event: TailEvent, found: FileIdentity) -> None:
        result.event = event
        result.lost_bytes = len(self.pending)
        self._reset()
        self.identity = found

    def _head(self) -> bytes:
        """The opening bytes of the file, however few there are."""
        try:
            with self.path.open("rb") as handle:
                return handle.read(SIGNATURE_BYTES)
        except FileNotFoundError:
            return b""

    def _remember_head(self) -> None:
        """Grow the signature until it is a full window, then leave it alone."""
        if len(self.signature) < SIGNATURE_BYTES:
            self.signature = self._head()

    def _read_chunk(self) -> bytes:
        with self.path.open("rb") as handle:
            handle.seek(self.offset, os.SEEK_SET)
            return handle.read(self.chunk_size)

    def _reset(self) -> None:
        self.offset = 0
        self.pending = b""
        self.identity = None
        self.signature = b""

    @property
    def buffered(self) -> int:
        """Bytes held back because they do not form a complete line yet."""
        return len(self.pending)

    def seek_to(self, offset: int, identity: FileIdentity | None = None) -> None:
        """Resume from a checkpoint."""
        if offset < 0:
            raise IngestError("cannot resume from a negative offset", detail=str(offset))
        self.offset = offset
        self.pending = b""
        self.identity = identity
        self.signature = self._head() if offset else b""
