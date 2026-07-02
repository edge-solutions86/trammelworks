"""The indexes have to match the expression the queries actually use.

Every read of this archive orders by ``COALESCE(corrected_ns, observed_ns)``,
because the question a dispute review asks is what happened at 14:07 in the
real world rather than what the drifting clock in the cabinet believed. The
first three indexes were built on ``observed_ns``, and SQLite cannot use an
index on a column to satisfy an expression over that column, so the main read
path was a full scan and a temporary B-tree for the sort.

These tests pin the plans rather than the index names. A rename is fine; going
back to a scan is not.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from stanchion.archive.repository import ORDER_COLUMN, Repository
from stanchion.archive.schema import (
    RETIRED_INDEXES,
    SCHEMA_VERSION,
    connect,
    create,
    index_names,
    migrate,
    open_archive,
    read_version,
)
from stanchion.records import LogRecord, Stream
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-06-10T12:00:00Z")

OLD_INDEXES = (
    "CREATE INDEX IF NOT EXISTS records_by_time ON records (observed_ns)",
    "CREATE INDEX IF NOT EXISTS records_by_unit ON records (unit, observed_ns)",
    "CREATE INDEX IF NOT EXISTS records_by_class ON records (klass, observed_ns)",
)


@pytest.fixture
def path(tmp_path: Path) -> Path:
    return tmp_path / "archive" / "records.sqlite3"


@pytest.fixture
def connection(path: Path) -> sqlite3.Connection:
    return open_archive(path)


def plan(connection: sqlite3.Connection, sql: str, *params: object) -> list[str]:
    return [str(row[3]) for row in connection.execute(f"EXPLAIN QUERY PLAN {sql}", params)]


def fill(connection: sqlite3.Connection, count: int = 50) -> None:
    repo = Repository(connection=connection)
    repo.add_all(
        [
            LogRecord(
                site="US-TX-0417",
                unit="pump-bridge" if index % 2 else "price-sign",
                stream=Stream.STDOUT,
                observed_at=NanoTime(NOW.epoch_ns + index),
                message=f"line {index}",
            )
            for index in range(count)
        ],
        NOW,
    )


def make_old(path: Path) -> sqlite3.Connection:
    """An archive as an earlier version of this software left it."""
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = connect(path)
    create(connection)
    with connection:
        for name in ("records_by_best_time", "records_by_unit_time", "records_by_class_time"):
            connection.execute(f"DROP INDEX IF EXISTS {name}")
        for statement in OLD_INDEXES:
            connection.execute(statement)
        connection.execute(
            "INSERT OR REPLACE INTO meta (name, value) VALUES ('schema_version', '1')"
        )
    return connection


def test_a_fresh_archive_is_at_the_current_version(connection: sqlite3.Connection) -> None:
    assert read_version(connection) == SCHEMA_VERSION


def test_a_fresh_archive_has_the_indexes_the_queries_need(
    connection: sqlite3.Connection,
) -> None:
    found = index_names(connection)
    assert "records_by_best_time" in found
    assert "records_by_unit_time" in found
    assert "records_by_class_time" in found


def test_a_fresh_archive_carries_none_of_the_retired_indexes(
    connection: sqlite3.Connection,
) -> None:
    found = index_names(connection)
    assert not set(found) & set(RETIRED_INDEXES)


def test_a_time_window_uses_an_index(connection: sqlite3.Connection) -> None:
    fill(connection)
    steps = plan(connection, f"SELECT * FROM records WHERE {ORDER_COLUMN} > 5")
    assert any("records_by_best_time" in step for step in steps)


def test_ordering_by_time_needs_no_temporary_sort(connection: sqlite3.Connection) -> None:
    # The temp B-tree is the expensive half on an SD card.
    fill(connection)
    steps = plan(
        connection,
        f"SELECT * FROM records WHERE {ORDER_COLUMN} > 5 ORDER BY {ORDER_COLUMN} ASC, id ASC",
    )
    assert not any("TEMP B-TREE" in step.upper() for step in steps)


def test_a_unit_filter_uses_its_own_index(connection: sqlite3.Connection) -> None:
    fill(connection)
    steps = plan(
        connection,
        f"SELECT * FROM records WHERE unit = ? ORDER BY {ORDER_COLUMN} ASC, id ASC",
        "pump-bridge",
    )
    assert any("records_by_unit_time" in step for step in steps)


def test_a_class_filter_uses_its_own_index(connection: sqlite3.Connection) -> None:
    fill(connection)
    steps = plan(
        connection,
        f"SELECT * FROM records WHERE klass = ? ORDER BY {ORDER_COLUMN} ASC",
        "standard",
    )
    assert any("records_by_class_time" in step for step in steps)


def test_the_cursor_predicate_uses_an_index(connection: sqlite3.Connection) -> None:
    fill(connection)
    steps = plan(
        connection,
        f"SELECT * FROM records WHERE (({ORDER_COLUMN} > ?)"
        f" OR ({ORDER_COLUMN} = ? AND id > ?)) ORDER BY {ORDER_COLUMN} ASC, id ASC",
        1,
        1,
        1,
    )
    assert not any("SCAN records" == step for step in steps)


def test_an_old_archive_reports_the_old_version(path: Path) -> None:
    assert read_version(make_old(path)) == 1


def test_an_old_archive_migrates(path: Path) -> None:
    connection = make_old(path)
    assert migrate(connection) == SCHEMA_VERSION
    assert read_version(connection) == SCHEMA_VERSION


def test_migrating_adds_the_new_indexes(path: Path) -> None:
    connection = make_old(path)
    migrate(connection)
    assert "records_by_best_time" in index_names(connection)


def test_migrating_drops_the_ones_nothing_can_use(path: Path) -> None:
    # An index that is never read is still written on every insert, and on
    # this hardware that is the half that costs.
    connection = make_old(path)
    assert set(index_names(connection)) & set(RETIRED_INDEXES)
    migrate(connection)
    assert not set(index_names(connection)) & set(RETIRED_INDEXES)


def test_migrating_keeps_the_records(path: Path) -> None:
    connection = make_old(path)
    fill(connection, 30)
    migrate(connection)
    assert Repository(connection=connection).count() == 30


def test_migrating_twice_changes_nothing(path: Path) -> None:
    connection = make_old(path)
    migrate(connection)
    before = index_names(connection)
    assert migrate(connection) == SCHEMA_VERSION
    assert index_names(connection) == before


def test_a_current_archive_is_left_alone(connection: sqlite3.Connection) -> None:
    before = index_names(connection)
    assert migrate(connection) == SCHEMA_VERSION
    assert index_names(connection) == before


def test_opening_an_old_archive_migrates_it(path: Path) -> None:
    make_old(path).close()
    connection = open_archive(path)
    assert read_version(connection) == SCHEMA_VERSION
    assert "records_by_best_time" in index_names(connection)


def test_opening_read_only_does_not_try_to_migrate(path: Path) -> None:
    # A reader has no business changing the file, and on a site where the
    # collector holds it open a reader that tried would fail rather than read.
    make_old(path).close()
    connection = open_archive(path, read_only=True)
    assert read_version(connection) == 1


def test_a_migrated_archive_answers_the_same_records(path: Path) -> None:
    connection = make_old(path)
    fill(connection, 20)
    migrate(connection)
    found = Repository(connection=connection).between(NanoTime(0), NanoTime(NOW.epoch_ns + 1000))
    assert [item.message for item in found] == [f"line {index}" for index in range(20)]


def test_a_migrated_archive_uses_the_new_index(path: Path) -> None:
    connection = make_old(path)
    fill(connection, 20)
    migrate(connection)
    steps = plan(connection, f"SELECT * FROM records WHERE {ORDER_COLUMN} > 5")
    assert any("records_by_best_time" in step for step in steps)
