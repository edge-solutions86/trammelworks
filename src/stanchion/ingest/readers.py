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

The two line oriented formats differ only in how one line is decoded, so the
walk over the buffer lives in one place and each format supplies the decode. The
journal is genuinely different: its unit is a block ended by a blank line, not a
line ended by a newline, so it keeps its own walk.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Protocol

from stanchion.errors import ParseError
from stanchion.ingest.cri import parse_cri_line
from stanchion.ingest.detect import Format
from stanchion.ingest.journal import JournalEntry, parse_journal_export
from stanchion.ingest.jsonfile import parse_jsonfile_line
from stanchion.records import Fragment, SourceRef, Stream
from stanchion.timestamps import NS_PER_US, NanoTime

TERMINATOR = b"\n"
BLOCK_TERMINATOR = b"\n\n"

#: Labels the journal reader passes through for clock correction to use. They
#: name a real position in a boot session, which is the one thing the other two
#: formats cannot supply.
MONOTONIC_LABEL = "monotonic_ns"
BOOT_LABEL = "boot_id"


class ParsedLine(Protocol):
    """What both line formats produce once decoded."""

    @property
    def timestamp(self) -> NanoTime: ...

    @property
    def stream(self) -> Stream: ...

    @property
    def message(self) -> str: ...

    @property
    def partial(self) -> bool: ...

    @property
    def lossy(self) -> bool: ...


LineParser = Callable[..., ParsedLine]


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


@dataclass(frozen=True, slots=True)
class LineReader:
    """The shared walk over a newline delimited buffer."""

    parse: LineParser

    def read(self, data: bytes, *, path: str, base_offset: int = 0) -> ReadResult:
        result = ReadResult()
        start = 0
        while True:
            end = data.find(TERMINATOR, start)
            if end == -1:
                return result
            raw = data[start : end + 1]
            offset = start
            start = end + 1
            result.consumed = start
            if not raw.strip():
                result.skipped += 1
                continue
            try:
                line = self.parse(raw, source=path, offset=base_offset + offset)
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


def CriReader() -> LineReader:
    """Reads the CRI text format."""
    return LineReader(parse=parse_cri_line)


def JsonFileReader() -> LineReader:
    """Reads the docker json-file format."""
    return LineReader(parse=parse_jsonfile_line)


class JournalReader:
    """Reads the journald export format, whose unit is a block not a line.

    This is the one format that knows where in the boot session its entry sits.
    ``_BOOT_ID`` and ``__MONOTONIC_TIMESTAMP`` are carried through as labels so
    that clock correction can use the real position rather than estimating one
    from the wall clock it is trying to correct.
    """

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
                    fragment = _from_journal(entry, path, base_offset + offset, len(block))
                except ParseError as exc:
                    result.skipped += 1
                    result.errors.append(exc)
                    continue
                result.fragments.append(fragment)
        return result


def _from_journal(entry: JournalEntry, path: str, offset: int, length: int) -> Fragment:
    """One journal entry as a fragment, position labels included."""
    labels: dict[str, str] = {}
    monotonic = entry.monotonic_us
    if monotonic is not None:
        labels[MONOTONIC_LABEL] = str(monotonic * NS_PER_US)
    if entry.boot_id is not None:
        labels[BOOT_LABEL] = entry.boot_id
    return Fragment(
        observed_at=entry.realtime,
        stream=entry.stream,
        message=entry.message,
        source=SourceRef(path=path, offset=offset, length=length),
        partial=False,
        labels=labels,
    )


def reader_for(fmt: Format) -> FragmentReader:
    """The reader that goes with a detected format."""
    if fmt is Format.CRI:
        return CriReader()
    if fmt is Format.JSONFILE:
        return JsonFileReader()
    return JournalReader()
