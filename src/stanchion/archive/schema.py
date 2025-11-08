"""The on site record store.

The spool is an outbound queue and it empties. The archive is the copy that
stays, and it exists because of one workflow: a customer disputes a transaction
six weeks later, the estate asks the site what happened at 14:07 that Tuesday,
and the answer has to come from the box rather than from a data warehouse that
may or may not have received it.

SQLite because the alternative on a controller with 2 GB of RAM and an SD card
is a file format we would have to write ourselves. The pragmas are the
interesting part:

* **WAL**, so a query from the support tool does not block the writer. Without
  it an engineer running a report stops the site collecting.
* **synchronous NORMAL**, not FULL. A power cut can lose the last transaction
  and that is acceptable here: those records are also in the spool, which has
  its own recovery. FULL costs an fsync per commit on an SD card that is already
  the slowest thing on the box.
* **busy_timeout**, because two processes really do write here: the collector
  and, briefly, the retention sweep.

The schema is deliberately flat. Labels go in as JSON rather than a second table
because every query anyone has ever asked for is by time, unit or text, and a
join costs more than it buys.
"""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from pathlib import Path

from stanchion.errors import StanchionError

SCHEMA_VERSION = 1
BUSY_TIMEOUT_MS = 5000

DDL = (
    """
    CREATE TABLE IF NOT EXISTS records (
        id            INTEGER PRIMARY KEY,
        key           TEXT    NOT NULL UNIQUE,
        site          TEXT    NOT NULL,
        unit          TEXT    NOT NULL,
        klass         TEXT    NOT NULL,
        stream        TEXT    NOT NULL,
        observed_ns   INTEGER NOT NULL,
        corrected_ns  INTEGER,
        confidence    TEXT    NOT NULL DEFAULT 'unknown',
        message       TEXT    NOT NULL,
        labels        TEXT    NOT NULL DEFAULT '{}',
        stored_ns     INTEGER NOT NULL
    )
    """,
    "CREATE INDEX IF NOT EXISTS records_by_time ON records (observed_ns)",
    "CREATE INDEX IF NOT EXISTS records_by_unit ON records (unit, observed_ns)",
    "CREATE INDEX IF NOT EXISTS records_by_class ON records (klass, observed_ns)",
    """
    CREATE TABLE IF NOT EXISTS holds (
        name        TEXT PRIMARY KEY,
        reason      TEXT NOT NULL,
        placed_ns   INTEGER NOT NULL,
        from_ns     INTEGER,
        until_ns    INTEGER
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS meta (
        name  TEXT PRIMARY KEY,
        value TEXT NOT NULL
    )
    """,
)

PRAGMAS = (
    "PRAGMA journal_mode=WAL",
    "PRAGMA synchronous=NORMAL",
    f"PRAGMA busy_timeout={BUSY_TIMEOUT_MS}",
    "PRAGMA foreign_keys=ON",
)


@dataclass(frozen=True, slots=True)
class ArchiveInfo:
    """What an opened archive says about itself."""

    path: Path
    version: int
    records: int

    @property
    def empty(self) -> bool:
        return self.records == 0


def connect(path: Path | str, read_only: bool = False) -> sqlite3.Connection:
    """Open the archive, applying the pragmas every connection needs."""
    target = Path(path)
    if read_only:
        if not target.exists():
            raise StanchionError("archive does not exist", detail=str(target))
        uri = f"file:{target.as_posix()}?mode=ro"
        connection = sqlite3.connect(uri, uri=True, isolation_level=None)
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(target, isolation_level=None)
    connection.row_factory = sqlite3.Row
    try:
        for pragma in PRAGMAS:
            if read_only and "journal_mode" in pragma:
                continue
            connection.execute(pragma)
    except sqlite3.DatabaseError as exc:
        # The first pragma is where a file that is not a database announces
        # itself. Wrapping it here means every caller sees one error type.
        connection.close()
        raise StanchionError("archive is not a database", detail=str(exc)) from exc
    return connection


def create(connection: sqlite3.Connection) -> None:
    """Build the tables if they are not there."""
    with connection:
        for statement in DDL:
            connection.execute(statement)
        connection.execute(
            "INSERT OR IGNORE INTO meta (name, value) VALUES ('schema_version', ?)",
            (str(SCHEMA_VERSION),),
        )


def read_version(connection: sqlite3.Connection) -> int:
    """The schema version this file was written with."""
    try:
        row = connection.execute("SELECT value FROM meta WHERE name = 'schema_version'").fetchone()
    except sqlite3.OperationalError:
        # No meta table at all. An empty file rather than a damaged one, so it
        # reads as version zero and the caller creates what it needs.
        return 0
    except sqlite3.DatabaseError as exc:
        raise StanchionError("archive is not readable", detail=str(exc)) from exc
    if row is None:
        return 0
    try:
        return int(row["value"])
    except (TypeError, ValueError) as exc:
        raise StanchionError("archive version is not a number") from exc


def open_archive(path: Path | str, read_only: bool = False) -> sqlite3.Connection:
    """Open, create if needed, and refuse a file from a future version."""
    connection = connect(path, read_only=read_only)
    if not read_only:
        create(connection)
    version = read_version(connection)
    if version > SCHEMA_VERSION:
        connection.close()
        raise StanchionError("archive was written by a newer stanchion", detail=str(version))
    return connection


def describe(connection: sqlite3.Connection, path: Path | str) -> ArchiveInfo:
    count = connection.execute("SELECT count(*) AS n FROM records").fetchone()["n"]
    return ArchiveInfo(path=Path(path), version=read_version(connection), records=int(count))
