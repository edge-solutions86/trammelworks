"""Turning a buffer of bytes into fragments.

The tailer hands over whatever it managed to read, which is almost never a whole
number of lines. A reader therefore returns two things: the fragments it could
build, and how many bytes it consumed. The caller keeps the remainder and hands
it back with more bytes next time.

Consuming only what is complete is the rule that makes resume safe. If a reader
consumed a half written line, the checkpoint would advance past bytes that had
not been parsed, and the second half of that line would come back next time as a
line of its own with no beginning.

A line that will not parse is skipped rather than fatal. One bad line in a file
is a bad line; refusing the file loses everything after it, which on a forecourt
is the rest of the shift.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

from stanchion.errors import ParseError
from stanchion.ingest.cri import parse_cri_line
from stanchion.ingest.journal import parse_journal_export
from stanchion.ingest.jsonfile import parse_jsonfile_line
from stanchion.records import Fragment, SourceRef

TERMINATOR = b"\n"
BLOCK_TERMINATOR = b"\n\n"


@dataclass(slots=True)
class ReadResult:
    """What one pass over a buffer produced."""

    fragments: list[Fragment] = field(default_factory=list)
    consumed: int = 0
    skipped: int = 0
    errors: list[ParseError] = field(default_factory=list)


class FragmentReader(Protocol):
    """Reads a buffer into fragments, consuming only complete units."""

    def read(self, data: bytes, *, path: str, base_offset: int = 0) -> ReadResult: ...


def _iter_lines(data: bytes) -> list[tuple[int, bytes]]:
    """Split into complete lines, keeping each one's offset in the buffer."""
    out: list[tuple[int, bytes]] = []
    start = 0
    while True:
        end = data.find(TERMINATOR, start)
        if end == -1:
            return out
        out.append((start, data[start : end + 1]))
        start = end + 1


class CriReader:
    """Reads the CRI text format."""

    __slots__ = ()

    def read(self, data: bytes, *, path: str, base_offset: int = 0) -> ReadResult:
        result = ReadResult()
        for offset, raw in _iter_lines(data):
            result.consumed = offset + len(raw)
            if not raw.strip():
                result.skipped += 1
                continue
            try:
                line = parse_cri_line(raw, source=path, offset=base_offset + offset)
            except ParseError as exc:
                result.skipped += 1
                result.errors.append(exc)
                continue
            result.fragments.append(
                Fragment(
                    observed_at=line.timestamp,
                    stream=line.stream,
                    message=line.message,
                    source=SourceRef(path=path, offset=base_offset + offset, length=len(raw)),
                    partial=line.partial,
                    lossy=line.lossy,
                )
            )
        return result


class JsonFileReader:
    """Reads the docker json-file format."""

    __slots__ = ()

    def read(self, data: bytes, *, path: str, base_offset: int = 0) -> ReadResult:
        result = ReadResult()
        for offset, raw in _iter_lines(data):
            result.consumed = offset + len(raw)
            if not raw.strip():
                result.skipped += 1
                continue
            try:
                line = parse_jsonfile_line(raw, source=path, offset=base_offset + offset)
            except ParseError as exc:
                result.skipped += 1
                result.errors.append(exc)
                continue
            result.fragments.append(
                Fragment(
                    observed_at=line.timestamp,
                    stream=line.stream,
                    message=line.message,
                    source=SourceRef(path=path, offset=base_offset + offset, length=len(raw)),
                    partial=line.partial,
                    lossy=line.lossy,
                )
            )
        return result


class JournalReader:
    """Reads the journald export format, whose unit is a block not a line."""

    __slots__ = ()

    def read(self, data: bytes, *, path: str, base_offset: int = 0) -> ReadResult:
        result = ReadResult()
        start = 0
        while True:
            end = data.find(BLOCK_TERMINATOR, start)
            if end == -1:
                break
            block = data[start : end + len(BLOCK_TERMINATOR)]
            offset = start
            start = end + len(BLOCK_TERMINATOR)
            result.consumed = start
            try:
                entries = parse_journal_export(block)
            except ParseError as exc:
                result.skipped += 1
                result.errors.append(exc)
                continue
            for entry in entries:
                try:
                    stamp = entry.realtime
                    stream = entry.stream
                except ParseError as exc:
                    result.skipped += 1
                    result.errors.append(exc)
                    continue
                result.fragments.append(
                    Fragment(
                        observed_at=stamp,
                        stream=stream,
                        message=entry.message,
                        source=SourceRef(path=path, offset=base_offset + offset, length=len(block)),
                        partial=False,
                    )
                )
        return result
