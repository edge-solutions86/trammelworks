"""Putting records into the archive and getting them back out.

Writes are idempotent on the record key. The collector can be told to re-read a
file, a segment can be replayed off a stick, and a support engineer can import
the same export twice; none of those should double the archive. ``INSERT OR
IGNORE`` on the unique key does that in one statement, which matters because the
alternative is a select per record on an SD card.

Reads are always bounded by time and always ordered by it. There is no "give me
everything" call, because on a busy site that is four million rows and the tool
asking for it is running on a laptop over a VPN. A caller who genuinely wants
the lot asks for a window that covers it and pages.

Ordering is by the corrected time where there is one, falling back to what the
box claimed. That is the ordering a dispute review needs: the question is what
happened in the real world at 14:07, not what the drifting clock in the cabinet
believed at the time.
"""

from __future__ import annotations

import json
import sqlite3
from collections.abc import Iterable, Iterator
from dataclasses import dataclass, field
from typing import Any

from stanchion.errors import StanchionError
from stanchion.identity import record_key
from stanchion.records import LogRecord, SourceRef, Stream
from stanchion.spool.priority import PriorityTable
from stanchion.timestamps import NanoTime

DEFAULT_LIMIT = 1000

#: Rows per transaction. Large enough that the commit cost disappears into the
#: write, small enough that the lock is never held for long.
DEFAULT_BATCH = 500
MAX_LIMIT = 100_000

ORDER_COLUMN = "COALESCE(corrected_ns, observed_ns)"

INSERT = """
INSERT OR IGNORE INTO records
    (key, site, unit, klass, stream, observed_ns, corrected_ns, confidence,
     message, labels, stored_ns)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""


@dataclass(frozen=True, slots=True)
class StoredRecord:
    """A record as the archive holds it."""

    key: str
    site: str
    unit: str
    klass: str
    stream: Stream
    observed_at: NanoTime
    corrected_at: NanoTime | None
    confidence: str
    message: str
    labels: dict[str, str]
    stored_at: NanoTime

    @property
    def best_at(self) -> NanoTime:
        return self.corrected_at if self.corrected_at is not None else self.observed_at

    def to_record(self) -> LogRecord:
        return LogRecord(
            site=self.site,
            unit=self.unit,
            stream=self.stream,
            observed_at=self.observed_at,
            message=self.message,
            sources=(),
            labels=dict(self.labels),
            corrected_at=self.corrected_at,
            clock_confidence=self.confidence,
        )


def row_to_record(row: sqlite3.Row) -> StoredRecord:
    try:
        labels = json.loads(row["labels"])
    except (TypeError, json.JSONDecodeError):
        labels = {}
    corrected = row["corrected_ns"]
    return StoredRecord(
        key=row["key"],
        site=row["site"],
        unit=row["unit"],
        klass=row["klass"],
        stream=Stream.parse(row["stream"]),
        observed_at=NanoTime(int(row["observed_ns"])),
        corrected_at=None if corrected is None else NanoTime(int(corrected)),
        confidence=row["confidence"],
        message=row["message"],
        labels={str(k): str(v) for k, v in labels.items()} if isinstance(labels, dict) else {},
        stored_at=NanoTime(int(row["stored_ns"])),
    )


@dataclass(slots=True)
class Repository:
    """Everything the archive is asked to do with records."""

    connection: sqlite3.Connection
    table: PriorityTable = field(default_factory=PriorityTable)
    batch: int = DEFAULT_BATCH
    written: int = 0
    duplicates: int = 0

    def __post_init__(self) -> None:
        if self.batch < 1:
            raise StanchionError("the archive batch must be at least one")

    def _row(self, record: LogRecord, stored_at: NanoTime) -> tuple[Any, ...]:
        """One record as the insert statement wants it."""
        return (
            record_key(record),
            record.site,
            record.unit,
            self.table.class_for(record.unit).name,
            record.stream.value,
            record.observed_at.epoch_ns,
            None if record.corrected_at is None else record.corrected_at.epoch_ns,
            record.clock_confidence,
            record.message,
            json.dumps(record.labels, sort_keys=True),
            stored_at.epoch_ns,
        )

    def add(self, record: LogRecord, stored_at: NanoTime) -> bool:
        """Store one record. Returns whether it was new."""
        return self.add_all([record], stored_at) == 1

    def add_all(self, records: Iterable[LogRecord], stored_at: NanoTime) -> int:
        """Store many in as few transactions as it takes. Returns how many were new.

        One transaction per chunk rather than per record. A collect pass hands
        this a few thousand records at a time and a backfill hands it a day's
        worth, and a commit per record on an SD card is the difference between
        a pass that finishes and one the operator kills.

        The count of new records comes from the connection's total change
        counter rather than from ``rowcount``, which reports -1 for an
        ``executemany`` and would have every caller believing nothing was
        written.

        Chunked rather than done in one go, because a single transaction over
        four hundred thousand rows holds the write lock for as long as it takes
        and the collector is trying to use the same file.
        """
        added = 0
        seen = 0
        for chunk in _chunks(records, self.batch):
            seen += len(chunk)
            rows = [self._row(record, stored_at) for record in chunk]
            before = self.connection.total_changes
            with self.connection:
                self.connection.executemany(INSERT, rows)
            added += self.connection.total_changes - before
        self.written += added
        self.duplicates += seen - added
        return added

    def count(self) -> int:
        return int(self.connection.execute("SELECT count(*) AS n FROM records").fetchone()["n"])

    def between(
        self,
        start: NanoTime,
        end: NanoTime,
        unit: str | None = None,
        klass: str | None = None,
        limit: int = DEFAULT_LIMIT,
        offset: int = 0,
    ) -> list[StoredRecord]:
        """Records inside a window, oldest first."""
        if end < start:
            raise StanchionError("the window ends before it starts")
        if not 1 <= limit <= MAX_LIMIT:
            raise StanchionError("limit is out of range", detail=str(limit))
        if offset < 0:
            raise StanchionError("offset cannot be negative", detail=str(offset))
        clauses = [f"{ORDER_COLUMN} >= ?", f"{ORDER_COLUMN} <= ?"]
        values: list[Any] = [start.epoch_ns, end.epoch_ns]
        if unit is not None:
            clauses.append("unit = ?")
            values.append(unit)
        if klass is not None:
            clauses.append("klass = ?")
            values.append(klass)
        sql = (
            "SELECT * FROM records WHERE "
            + " AND ".join(clauses)
            + f" ORDER BY {ORDER_COLUMN} ASC, id ASC LIMIT ? OFFSET ?"
        )
        values.extend([limit, offset])
        return [row_to_record(row) for row in self.connection.execute(sql, values)]

    def units(self) -> tuple[str, ...]:
        rows = self.connection.execute("SELECT DISTINCT unit FROM records ORDER BY unit")
        return tuple(row["unit"] for row in rows)

    def span(self) -> tuple[NanoTime, NanoTime] | None:
        """The oldest and newest record, or None when there are none."""
        row = self.connection.execute(
            f"SELECT min({ORDER_COLUMN}) AS lo, max({ORDER_COLUMN}) AS hi FROM records"
        ).fetchone()
        if row is None or row["lo"] is None:
            return None
        return NanoTime(int(row["lo"])), NanoTime(int(row["hi"]))

    def counts_by_class(self) -> dict[str, int]:
        rows = self.connection.execute(
            "SELECT klass, count(*) AS n FROM records GROUP BY klass ORDER BY klass"
        )
        return {row["klass"]: int(row["n"]) for row in rows}


def source_refs(record: StoredRecord) -> tuple[SourceRef, ...]:
    """The archive does not keep byte spans, so this is always empty.

    Present so that a caller moving between a live record and a stored one does
    not have to special case the difference. The spans belong to files that a
    rotation has usually already removed by the time anyone asks.
    """
    return ()


def _chunks(records: Iterable[LogRecord], size: int) -> Iterator[list[LogRecord]]:
    """Walk an iterable in fixed size pieces, without materialising all of it."""
    held: list[LogRecord] = []
    for record in records:
        held.append(record)
        if len(held) >= size:
            yield held
            held = []
    if held:
        yield held
