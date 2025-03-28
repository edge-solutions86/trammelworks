"""The journald export format.

Sites commissioned with the systemd journal driver present this instead of files
per container. An entry is a run of ``NAME=value`` lines ended by a blank line::

    __REALTIME_TIMESTAMP=1741087800123456
    __MONOTONIC_TIMESTAMP=98765432100
    _BOOT_ID=4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d
    PRIORITY=6
    CONTAINER_NAME=payment-kernel
    MESSAGE=auth approved

A value that contains a newline cannot be written that way, so the exporter
switches to a binary form: the field name alone on a line, then eight bytes of
little endian length, then the raw value, then a newline. A parser that only
handles the text form silently loses exactly the entries worth having, because a
multi line value is usually a stack trace.

Two fields here are worth more than the message. ``_BOOT_ID`` and
``__MONOTONIC_TIMESTAMP`` together identify a boot session and a position inside
it, which is the only ordering on a site controller that a drifting RTC cannot
corrupt.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field

from stanchion.errors import ParseError
from stanchion.records import Stream
from stanchion.timestamps import NanoTime

LENGTH_PREFIX = 8
REALTIME = "__REALTIME_TIMESTAMP"
MONOTONIC = "__MONOTONIC_TIMESTAMP"
BOOT_ID = "_BOOT_ID"
MESSAGE = "MESSAGE"
PRIORITY = "PRIORITY"
CONTAINER_NAME = "CONTAINER_NAME"

#: syslog severities. The container runtime maps stdout to informational and
#: stderr to error, so anything at error or worse came from the error stream.
PRIORITY_ERROR = 3


@dataclass(frozen=True, slots=True)
class JournalEntry:
    """One journal entry, with the fields it actually carried."""

    fields: dict[str, str] = field(default_factory=dict)
    binary: frozenset[str] = frozenset()

    def get(self, name: str, default: str = "") -> str:
        return self.fields.get(name, default)

    @property
    def message(self) -> str:
        return self.get(MESSAGE)

    @property
    def realtime(self) -> NanoTime:
        raw = self.fields.get(REALTIME)
        if raw is None:
            raise ParseError("journal entry has no realtime timestamp")
        try:
            return NanoTime.from_micros(int(raw))
        except ValueError as exc:
            raise ParseError("journal realtime is not an integer", detail=raw) from exc

    @property
    def monotonic_us(self) -> int | None:
        raw = self.fields.get(MONOTONIC)
        if raw is None:
            return None
        try:
            return int(raw)
        except ValueError as exc:
            raise ParseError("journal monotonic is not an integer", detail=raw) from exc

    @property
    def boot_id(self) -> str | None:
        return self.fields.get(BOOT_ID)

    @property
    def unit(self) -> str | None:
        return self.fields.get(CONTAINER_NAME)

    @property
    def stream(self) -> Stream:
        raw = self.fields.get(PRIORITY)
        if raw is None:
            return Stream.STDOUT
        try:
            level = int(raw)
        except ValueError as exc:
            raise ParseError("journal priority is not an integer", detail=raw) from exc
        return Stream.STDERR if level <= PRIORITY_ERROR else Stream.STDOUT


def _split_line(buffer: bytes, start: int) -> tuple[bytes, int]:
    end = buffer.find(b"\n", start)
    if end == -1:
        return buffer[start:], len(buffer)
    return buffer[start:end], end + 1


def parse_journal_export(data: bytes) -> list[JournalEntry]:
    """Parse a whole export stream into entries."""
    entries: list[JournalEntry] = []
    fields: dict[str, str] = {}
    binary: set[str] = set()
    position = 0
    while position < len(data):
        line, next_position = _split_line(data, position)
        if not line:
            if fields:
                entries.append(JournalEntry(fields=fields, binary=frozenset(binary)))
                fields, binary = {}, set()
            position = next_position
            continue
        if b"=" in line:
            name, _, value = line.partition(b"=")
            fields[name.decode("utf-8", errors="replace")] = value.decode("utf-8", errors="replace")
            position = next_position
            continue
        name_text = line.decode("utf-8", errors="replace")
        header = next_position
        if header + LENGTH_PREFIX > len(data):
            raise ParseError("journal binary field is truncated", detail=name_text)
        (size,) = struct.unpack("<Q", data[header : header + LENGTH_PREFIX])
        body_start = header + LENGTH_PREFIX
        body_end = body_start + size
        if body_end > len(data):
            raise ParseError("journal binary value runs past the end", detail=name_text)
        fields[name_text] = data[body_start:body_end].decode("utf-8", errors="replace")
        binary.add(name_text)
        position = body_end + 1 if data[body_end : body_end + 1] == b"\n" else body_end
    if fields:
        entries.append(JournalEntry(fields=fields, binary=frozenset(binary)))
    return entries


def render_journal_entry(entry: JournalEntry) -> bytes:
    """Render an entry back out, using the binary form where it is needed."""
    out = bytearray()
    for name, value in entry.fields.items():
        encoded = value.encode("utf-8")
        if b"\n" in encoded or name in entry.binary:
            out += name.encode("utf-8") + b"\n"
            out += struct.pack("<Q", len(encoded))
            out += encoded + b"\n"
        else:
            out += name.encode("utf-8") + b"=" + encoded + b"\n"
    out += b"\n"
    return bytes(out)
