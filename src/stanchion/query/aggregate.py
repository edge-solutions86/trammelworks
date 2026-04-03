"""Counting matches instead of listing them.

The question an incident review starts with is not "show me the lines", it is
"which unit was noisy and when did it start". Answering that by pulling four
million rows over a VPN and counting them on a laptop is what the archive is
supposed to make unnecessary.

So a filter can be grouped: by a field, or into time buckets, or both. What
comes back is one row per group with a count and the span it covers, which is
small enough to read and small enough to send.

The catch is the residual, and it is the same catch the executor has. A group
count computed by SQL counts every row the SQL matched, and when part of the
filter could not be pushed into SQL those are not the same rows. Grouping in the
database there would report counts for records that the filter excludes, and
they would look authoritative because they came back as numbers rather than as
lines somebody could check.

So the rule is blunt: **the database groups only when the plan pushed
everything.** Otherwise the rows are read and tallied here, under the same scan
ceiling and the same truncation flag as a search. Slower, and right.

Time buckets are counted from an origin rather than from the first record. Two
reports run an hour apart have to line up, and buckets anchored to whatever the
earliest matching record happened to be do not.
"""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field
from datetime import timedelta

from stanchion.archive.repository import row_to_record
from stanchion.errors import QueryError
from stanchion.query.ast import Query
from stanchion.query.evaluate import matches
from stanchion.query.execute import DEFAULT_SCAN_CEILING
from stanchion.query.plan import COLUMNS, TIME_COLUMN, plan
from stanchion.timestamps import NanoTime

#: What a count may be grouped by. Deliberately not ``message``: grouping four
#: million distinct strings produces four million groups and no insight.
GROUPABLE = frozenset({"site", "unit", "class", "stream", "confidence"})

#: The bucket used when a report asks for a histogram without saying how wide.
DEFAULT_BUCKET = timedelta(hours=1)

MIN_BUCKET = timedelta(seconds=1)

#: More groups than this and the answer is a listing, not a summary.
MAX_GROUPS = 5000

NS_PER_SECOND = 1_000_000_000


def check_group(name: str) -> str:
    cleaned = name.strip().lower()
    if cleaned not in GROUPABLE:
        raise QueryError("cannot group by this field", detail=name)
    return cleaned


def bucket_of(at_ns: int, width_ns: int, origin_ns: int = 0) -> int:
    """Which bucket an instant falls in, as the bucket's start.

    Floor division, so an instant before the origin lands in the bucket that
    contains it rather than the one after. Python's ``//`` already floors
    towards minus infinity, which is the behaviour wanted here and not the one
    C style truncation would give.
    """
    if width_ns < 1:
        raise QueryError("a bucket cannot be narrower than a nanosecond")
    return origin_ns + ((at_ns - origin_ns) // width_ns) * width_ns


@dataclass(frozen=True, slots=True)
class Group:
    """One line of a grouped count."""

    key: str
    count: int
    first_ns: int = 0
    last_ns: int = 0

    @property
    def first_at(self) -> NanoTime:
        return NanoTime(self.first_ns)

    @property
    def last_at(self) -> NanoTime:
        return NanoTime(self.last_ns)

    @property
    def span_ns(self) -> int:
        return max(self.last_ns - self.first_ns, 0)

    def __str__(self) -> str:
        return f"{self.key} {self.count}"


@dataclass(slots=True)
class Tally:
    """Groups being accumulated, and how they were arrived at."""

    groups: list[Group] = field(default_factory=list)
    scanned: int = 0
    post_filtered: int = 0
    truncated: bool = False
    in_database: bool = True

    @property
    def total(self) -> int:
        return sum(item.count for item in self.groups)

    @property
    def largest(self) -> Group | None:
        if not self.groups:
            return None
        return max(self.groups, key=lambda item: item.count)

    def describe(self) -> str:
        where = "in the archive" if self.in_database else "in memory"
        end = ", truncated" if self.truncated else ""
        return f"{len(self.groups)} groups, {self.total} records, counted {where}{end}"


@dataclass(slots=True)
class Counter:
    """Builds groups a row at a time."""

    counts: dict[str, int] = field(default_factory=dict)
    first: dict[str, int] = field(default_factory=dict)
    last: dict[str, int] = field(default_factory=dict)

    def add(self, key: str, at_ns: int, count: int = 1) -> None:
        self.counts[key] = self.counts.get(key, 0) + count
        if key not in self.first or at_ns < self.first[key]:
            self.first[key] = at_ns
        if key not in self.last or at_ns > self.last[key]:
            self.last[key] = at_ns

    def groups(self) -> list[Group]:
        """Biggest first, then by key so equal counts come back in a fixed order."""
        built = [
            Group(
                key=key,
                count=count,
                first_ns=self.first.get(key, 0),
                last_ns=self.last.get(key, 0),
            )
            for key, count in self.counts.items()
        ]
        built.sort(key=lambda item: (-item.count, item.key))
        return built


#: What a group with no value is called. Both counting paths have to use it or
#: the same archive answers two different ways depending on whether the filter
#: happened to be pushable, which is the one thing this module must not do.
NO_VALUE = "(none)"


def group_key(value: object) -> str:
    """The label a grouped value gets, empty and null alike."""
    if value is None:
        return NO_VALUE
    text = str(value)
    return text if text else NO_VALUE


def count_by(
    connection: sqlite3.Connection,
    query: Query,
    group: str,
    ceiling: int = DEFAULT_SCAN_CEILING,
) -> Tally:
    """Count matching records grouped by one field."""
    name = check_group(group)
    built = plan(query)
    if built.fully_pushed:
        return _count_in_database(connection, built.where_sql, built.params, COLUMNS[name])
    return _count_in_memory(connection, query, ceiling, group=name, width_ns=None)


def count_over_time(
    connection: sqlite3.Connection,
    query: Query,
    bucket: timedelta = DEFAULT_BUCKET,
    ceiling: int = DEFAULT_SCAN_CEILING,
) -> Tally:
    """Count matching records into equal time buckets."""
    if bucket < MIN_BUCKET:
        raise QueryError("a bucket must be at least a second", detail=str(bucket))
    width = int(bucket.total_seconds() * NS_PER_SECOND)
    built = plan(query)
    if built.fully_pushed:
        column = f"CAST({TIME_COLUMN} / {width} AS INTEGER) * {width}"
        return _count_in_database(connection, built.where_sql, built.params, column)
    return _count_in_memory(connection, query, ceiling, group=None, width_ns=width)


def _count_in_database(
    connection: sqlite3.Connection, where_sql: str, params: list[object], column: str
) -> Tally:
    statement = (
        f"SELECT {column} AS bucket, COUNT(*) AS n,"
        f" MIN({TIME_COLUMN}) AS first_ns, MAX({TIME_COLUMN}) AS last_ns"
        f" FROM records WHERE {where_sql} GROUP BY bucket ORDER BY n DESC, bucket ASC"
        f" LIMIT {MAX_GROUPS}"
    )
    try:
        rows = list(connection.execute(statement, params))
    except sqlite3.DatabaseError as exc:
        raise QueryError("the archive refused the count", detail=str(exc)) from exc
    counter = Counter()
    for row in rows:
        key = group_key(row["bucket"])
        counter.counts[key] = int(row["n"])
        counter.first[key] = int(row["first_ns"])
        counter.last[key] = int(row["last_ns"])
    tally = Tally(groups=counter.groups(), in_database=True)
    tally.scanned = tally.total
    tally.truncated = len(rows) >= MAX_GROUPS
    return tally


def _count_in_memory(
    connection: sqlite3.Connection,
    query: Query,
    ceiling: int,
    group: str | None,
    width_ns: int | None,
) -> Tally:
    """Read the rows and tally them here, because SQL cannot be trusted to.

    This is the path a label predicate forces. The residual has to be applied
    per record before anything is counted, and a database that grouped first
    would already have folded the rows it should have dropped into the numbers.
    """
    if ceiling < 1:
        raise QueryError("scan ceiling must be at least one", detail=str(ceiling))
    built = plan(query)
    statement = f"{built.sql()} LIMIT {ceiling}"
    try:
        rows = list(connection.execute(statement, built.params))
    except sqlite3.DatabaseError as exc:
        raise QueryError("the archive refused the count", detail=str(exc)) from exc
    tally = Tally(in_database=False, truncated=len(rows) >= ceiling)
    counter = Counter()
    for row in rows:
        tally.scanned += 1
        record = row_to_record(row)
        if built.residual is not None and not matches(built.residual, record):
            tally.post_filtered += 1
            continue
        at_ns = record.best_at.epoch_ns
        if width_ns is not None:
            key = str(bucket_of(at_ns, width_ns))
        else:
            key = group_key(_value_of(record, group or ""))
        counter.add(key, at_ns)
    tally.groups = counter.groups()[:MAX_GROUPS]
    return tally


def _value_of(record: object, name: str) -> str:
    """The grouped field's value on a stored record."""
    if name == "class":
        return str(getattr(record, "klass", ""))
    if name == "stream":
        return str(getattr(record, "stream", ""))
    return str(getattr(record, name, ""))
