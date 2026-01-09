"""Four ways to write records out, for four different readers.

* **text** for a person at a terminal. Columns line up, long messages are cut,
  and the width is fixed rather than measured from the data, because a table
  whose columns move between runs cannot be read down.
* **jsonl** for another program. One object per line, so a partial file is still
  usable and a stream can be processed as it arrives.
* **csv** for the spreadsheet a dispute team actually works in. The quoting
  matters more than it looks: a log message routinely contains commas, quotes
  and newlines, and getting that wrong shifts every column after it.
* **markdown** for the ticket. The same table as text, pasted into a case.

Every format writes both timestamps. A report that showed only the corrected
time would be asserting a conclusion; showing both lets the reader see how far
the box was out and decide what that is worth.
"""

from __future__ import annotations

import csv
import io
import json
from collections.abc import Iterable
from dataclasses import dataclass
from enum import StrEnum

from stanchion.archive.repository import StoredRecord
from stanchion.errors import StanchionError

DEFAULT_MESSAGE_WIDTH = 80
UNIT_WIDTH = 16
CLASS_WIDTH = 9

COLUMNS = ("time", "unit", "class", "stream", "confidence", "message")


class Format(StrEnum):
    """How a report is written."""

    TEXT = "text"
    JSONL = "jsonl"
    CSV = "csv"
    MARKDOWN = "markdown"


def parse_format(raw: str) -> Format:
    cleaned = raw.strip().lower()
    for member in Format:
        if member.value == cleaned:
            return member
    raise StanchionError(f"unknown report format {raw!r}")


@dataclass(frozen=True, slots=True)
class RenderOptions:
    """What the caller wants the output to look like."""

    fmt: Format = Format.TEXT
    width: int = DEFAULT_MESSAGE_WIDTH
    header: bool = True
    raw_time: bool = False

    def __post_init__(self) -> None:
        if self.width < 1:
            raise StanchionError("message width must be at least one")


def _cut(text: str, width: int) -> str:
    """One line, at most ``width`` characters, newlines made visible."""
    flat = text.replace("\n", "\\n").replace("\r", "")
    return flat if len(flat) <= width else flat[: width - 1] + "\u2026"


def _row(record: StoredRecord, options: RenderOptions) -> tuple[str, ...]:
    when = record.observed_at if options.raw_time else record.best_at
    return (
        when.isoformat(),
        record.unit,
        record.klass,
        record.stream.value,
        record.confidence,
        _cut(record.message, options.width),
    )


def render_text(records: Iterable[StoredRecord], options: RenderOptions) -> str:
    lines: list[str] = []
    if options.header:
        lines.append(
            f"{'time':<30} {'unit':<{UNIT_WIDTH}} {'class':<{CLASS_WIDTH}} {'stream':<6} message"
        )
    for record in records:
        when, unit, klass, stream, _, message = _row(record, options)
        lines.append(
            f"{when:<30} {unit:<{UNIT_WIDTH}} {klass:<{CLASS_WIDTH}} {stream:<6} {message}"
        )
    return "\n".join(lines)


def render_jsonl(records: Iterable[StoredRecord], options: RenderOptions) -> str:
    lines = []
    for record in records:
        payload = {
            "observed_at": record.observed_at.isoformat(),
            "site": record.site,
            "unit": record.unit,
            "class": record.klass,
            "stream": record.stream.value,
            "confidence": record.confidence,
            "message": record.message,
            "labels": dict(record.labels),
        }
        if record.corrected_at is not None:
            payload["corrected_at"] = record.corrected_at.isoformat()
        lines.append(json.dumps(payload, separators=(",", ":"), sort_keys=True))
    return "\n".join(lines)


def render_csv(records: Iterable[StoredRecord], options: RenderOptions) -> str:
    buffer = io.StringIO()
    # The dispute team opens this in a spreadsheet, so the quoting has to be the
    # dialect a spreadsheet expects rather than whatever is shortest.
    writer = csv.writer(buffer, lineterminator="\n", quoting=csv.QUOTE_MINIMAL)
    if options.header:
        writer.writerow(COLUMNS)
    for record in records:
        when = record.observed_at if options.raw_time else record.best_at
        writer.writerow(
            [
                when.isoformat(),
                record.unit,
                record.klass,
                record.stream.value,
                record.confidence,
                record.message,
            ]
        )
    return buffer.getvalue().rstrip("\n")


def render_markdown(records: Iterable[StoredRecord], options: RenderOptions) -> str:
    lines = [
        "| time | unit | class | stream | message |",
        "| --- | --- | --- | --- | --- |",
    ]
    for record in records:
        when, unit, klass, stream, _, message = _row(record, options)
        lines.append(f"| {when} | {unit} | {klass} | {stream} | {_pipe_safe(message)} |")
    return "\n".join(lines)


def _pipe_safe(text: str) -> str:
    """A bare pipe would end the cell, so it is escaped."""
    return text.replace("|", "\\|")


RENDERERS = {
    Format.TEXT: render_text,
    Format.JSONL: render_jsonl,
    Format.CSV: render_csv,
    Format.MARKDOWN: render_markdown,
}


def render(records: Iterable[StoredRecord], options: RenderOptions | None = None) -> str:
    """Render in whichever format was asked for."""
    chosen = options if options is not None else RenderOptions()
    return RENDERERS[chosen.fmt](list(records), chosen)
