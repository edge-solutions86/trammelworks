"""Paging through an archive that is still being written to.

A support engineer reading a shift asks for a thousand records, reads them, and
asks for the next thousand. The obvious way to do that is an offset, and on this
archive the obvious way is wrong twice over.

The first reason is that the collector is inserting while the tool is paging. An
offset counts rows from the start, so a record inserted ahead of the window
shifts everything down by one and page two begins with the line page one ended
with. Nobody notices, because a duplicated log line looks exactly like a log
line.

The second is that ``OFFSET 400000`` makes SQLite walk four hundred thousand
rows to throw them away, on an SD card, while the site is trying to write.

So paging is by key rather than by count. The cursor is the sort key of the last
record handed out: the corrected time, and the row id to break ties. Both are
needed. Two records really do share a nanosecond, because a container that
writes a burst gets one timestamp from the runtime for several lines, and a
cursor of time alone either repeats those lines or skips them depending on which
side of the comparison it lands.

The cursor is opaque to the caller by convention rather than by encryption.
There is nothing secret in it and pretending otherwise would only stop an
engineer from reading it in a support log, which is exactly when they need to.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from stanchion.errors import QueryError
from stanchion.timestamps import NanoTime

CURSOR_PATTERN = re.compile(r"^(?P<at>-?\d{1,20})\.(?P<row>\d{1,19})$")


@dataclass(frozen=True, slots=True)
class Cursor:
    """Where the last page stopped."""

    at_ns: int
    row_id: int

    def __post_init__(self) -> None:
        if self.row_id < 0:
            raise QueryError("a cursor row cannot be negative", detail=str(self.row_id))

    def encode(self) -> str:
        return f"{self.at_ns}.{self.row_id}"

    @property
    def at(self) -> NanoTime:
        return NanoTime(self.at_ns)

    def __str__(self) -> str:
        return self.encode()


def parse_cursor(raw: str) -> Cursor:
    """Read a cursor back, refusing anything that is not one."""
    match = CURSOR_PATTERN.match(raw.strip())
    if match is None:
        raise QueryError("cursor is malformed", detail=raw[:64])
    return Cursor(at_ns=int(match.group("at")), row_id=int(match.group("row")))


def cursor_sql(column: str) -> str:
    """The predicate that resumes after a cursor.

    Written as the expanded comparison rather than as a row value comparison,
    because ``(a, b) > (?, ?)`` is not available on every SQLite the site image
    has ever shipped with, and a query that works on the bench and not on a
    2019 controller is worse than a longer where clause.
    """
    return f"(({column} > ?) OR ({column} = ? AND id > ?))"


def cursor_params(cursor: Cursor) -> list[int]:
    """The three values ``cursor_sql`` expects, in order."""
    return [cursor.at_ns, cursor.at_ns, cursor.row_id]
