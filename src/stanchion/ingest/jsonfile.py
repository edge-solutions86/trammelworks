"""The docker json-file format.

One JSON object per line::

    {"log":"auth approved\\n","stream":"stdout","time":"2025-03-04T11:30:00.1Z"}

The difference from CRI is the one that matters and it is easy to miss: this
driver has no partial flag. A chunk that continues is signalled by the *absence*
of a trailing newline inside the ``log`` string. So the same physical fact,
"this line is not finished", is a present marker in one format and a missing
character in the other, and a reader that treats them the same way will either
join lines that were separate or split lines that were whole.

The last record of a live file is the awkward case. It has no newline because
the writer has not finished the line yet, which is indistinguishable from a
chunk that continues. The tailer resolves that with the read position; the
parser only reports what the bytes say.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

from stanchion.errors import ParseError
from stanchion.records import Stream
from stanchion.timestamps import NanoTime

LOG_KEY = "log"
STREAM_KEY = "stream"
TIME_KEY = "time"
ATTRS_KEY = "attrs"


@dataclass(frozen=True, slots=True)
class JsonFileLine:
    """One json-file record."""

    timestamp: NanoTime
    stream: Stream
    message: str
    partial: bool = False
    lossy: bool = False
    attrs: dict[str, str] = field(default_factory=dict)

    @property
    def complete(self) -> bool:
        return not self.partial


def _decode(raw: bytes) -> tuple[str, bool]:
    try:
        return raw.decode("utf-8"), False
    except UnicodeDecodeError:
        return raw.decode("utf-8", errors="replace"), True


def _attrs(payload: Any) -> dict[str, str]:
    if payload is None:
        return {}
    if not isinstance(payload, dict):
        raise ParseError("json-file attrs is not an object")
    return {str(key): str(value) for key, value in payload.items()}


def parse_jsonfile_line(raw: bytes, *, source: str | None = None, offset: int = 0) -> JsonFileLine:
    """Parse one json-file record."""
    body = raw.strip()
    if not body:
        raise ParseError("empty json-file line", source=source, offset=offset)
    text, lossy = _decode(body)
    try:
        payload = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ParseError("json-file line is not json", source=source, offset=offset) from exc
    if not isinstance(payload, dict):
        raise ParseError("json-file line is not an object", source=source, offset=offset)
    if LOG_KEY not in payload:
        raise ParseError("json-file line has no log field", source=source, offset=offset)
    message = payload[LOG_KEY]
    if not isinstance(message, str):
        raise ParseError("json-file log field is not a string", source=source, offset=offset)
    try:
        timestamp = NanoTime.parse(str(payload[TIME_KEY]))
    except KeyError as exc:
        raise ParseError("json-file line has no time field", source=source, offset=offset) from exc
    except ParseError as exc:
        raise ParseError("json-file line has a bad time", source=source, offset=offset) from exc
    try:
        stream = Stream.parse(str(payload.get(STREAM_KEY, "stdout")))
    except ValueError as exc:
        raise ParseError("json-file line has a bad stream", source=source, offset=offset) from exc
    partial = not message.endswith("\n")
    return JsonFileLine(
        timestamp=timestamp,
        stream=stream,
        message=message.removesuffix("\n"),
        partial=partial,
        lossy=lossy,
        attrs=_attrs(payload.get(ATTRS_KEY)),
    )


def render_jsonfile_line(record: JsonFileLine) -> bytes:
    """Render back to the wire form. The newline goes back inside ``log``."""
    payload: dict[str, Any] = {
        LOG_KEY: record.message if record.partial else record.message + "\n",
        STREAM_KEY: record.stream.value,
        TIME_KEY: record.timestamp.isoformat(),
    }
    if record.attrs:
        payload[ATTRS_KEY] = dict(record.attrs)
    return (json.dumps(payload) + "\n").encode("utf-8")
