"""The CRI log line format.

One line is::

    <rfc3339nano> <stream> <tags> <message>

with exactly three single space separators and everything after the third one
being the message, spaces and all. The tag field is a colon separated list whose
first element is ``F`` for a complete line or ``P`` for a chunk that continues.

Parsing happens on bytes rather than text, and that is not fussiness. The pump
controller bridge talks RS-485 and when a dispenser browns out it prints the raw
frame, so a percentage of lines on a live forecourt are not valid UTF-8. Decoding
the whole file strictly throws away the good lines around the bad one, and
decoding it loosely without saying so hides that anything happened. The parser
therefore decodes the message with replacement and reports that it did.
"""

from __future__ import annotations

from dataclasses import dataclass

from stanchion.errors import ParseError
from stanchion.records import Stream
from stanchion.timestamps import NanoTime

FULL_TAG = "F"
PARTIAL_TAG = "P"

SEPARATOR = b" "
FIELD_COUNT = 3


@dataclass(frozen=True, slots=True)
class CriLine:
    """One physical line, as the runtime wrote it."""

    timestamp: NanoTime
    stream: Stream
    message: str
    partial: bool = False
    tags: tuple[str, ...] = ()
    lossy: bool = False

    @property
    def complete(self) -> bool:
        return not self.partial


def _decode(raw: bytes) -> tuple[str, bool]:
    """Decode a message body, saying whether anything had to be replaced."""
    try:
        return raw.decode("utf-8"), False
    except UnicodeDecodeError:
        return raw.decode("utf-8", errors="replace"), True


def parse_tags(raw: str) -> tuple[bool, tuple[str, ...]]:
    """Split the tag field into the partial flag and whatever else is there."""
    parts = tuple(part for part in raw.split(":") if part)
    if not parts:
        raise ParseError("empty cri tag field")
    head = parts[0]
    if head == FULL_TAG:
        return False, parts[1:]
    if head == PARTIAL_TAG:
        return True, parts[1:]
    raise ParseError("unknown cri tag", detail=raw)


def parse_cri_line(raw: bytes, *, source: str | None = None, offset: int = 0) -> CriLine:
    """Parse one CRI line, without its terminator."""
    body = raw.rstrip(b"\r\n")
    if not body:
        raise ParseError("empty cri line", source=source, offset=offset)
    fields = body.split(SEPARATOR, FIELD_COUNT)
    if len(fields) < FIELD_COUNT:
        raise ParseError("cri line has too few fields", source=source, offset=offset)
    stamp_raw, stream_raw, tag_raw = fields[0], fields[1], fields[2]
    message_raw = fields[3] if len(fields) > FIELD_COUNT else b""
    try:
        timestamp = NanoTime.parse(stamp_raw.decode("ascii"))
    except (UnicodeDecodeError, ParseError) as exc:
        raise ParseError("cri line has no timestamp", source=source, offset=offset) from exc
    try:
        stream = Stream.parse(stream_raw.decode("ascii"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise ParseError("cri line has no stream", source=source, offset=offset) from exc
    try:
        partial, extra = parse_tags(tag_raw.decode("ascii"))
    except (UnicodeDecodeError, ParseError) as exc:
        raise ParseError("cri line has a bad tag", source=source, offset=offset) from exc
    message, lossy = _decode(message_raw)
    return CriLine(
        timestamp=timestamp,
        stream=stream,
        message=message,
        partial=partial,
        tags=extra,
        lossy=lossy,
    )


def render_cri_line(line: CriLine) -> bytes:
    """Render back to the wire form, used by the round trip tests and the sim."""
    tag = PARTIAL_TAG if line.partial else FULL_TAG
    if line.tags:
        tag = ":".join((tag, *line.tags))
    stamp = line.timestamp.isoformat()
    return f"{stamp} {line.stream.value} {tag} {line.message}\n".encode()
