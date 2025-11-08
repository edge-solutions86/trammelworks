from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from stanchion.archive.schema import (
    SCHEMA_VERSION,
    connect,
    create,
    describe,
    open_archive,
    read_version,
)
from stanchion.errors import StanchionError


@pytest.fixture
def archive_path(tmp_path: Path) -> Path:
    return tmp_path / "archive" / "records.sqlite3"


def test_opening_creates_the_file(archive_path: Path) -> None:
    connection = open_archive(archive_path)
    connection.close()
    assert archive_path.exists()


def test_opening_creates_the_parent_directory(archive_path: Path) -> None:
    open_archive(archive_path).close()
    assert archive_path.parent.is_dir()


def test_the_tables_are_there(archive_path: Path) -> None:
    connection = open_archive(archive_path)
    names = {
        row["name"]
        for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")
    }
    assert {"records", "holds", "meta"} <= names


def test_the_indexes_are_there(archive_path: Path) -> None:
    connection = open_archive(archive_path)
    names = {
        row["name"]
        for row in connection.execute("SELECT name FROM sqlite_master WHERE type='index'")
    }
    assert "records_by_time" in names
    assert "records_by_unit" in names
    assert "records_by_class" in names


def test_the_version_is_stamped(archive_path: Path) -> None:
    connection = open_archive(archive_path)
    assert read_version(connection) == SCHEMA_VERSION


def test_opening_twice_is_harmless(archive_path: Path) -> None:
    open_archive(archive_path).close()
    connection = open_archive(archive_path)
    assert read_version(connection) == SCHEMA_VERSION


def test_the_write_ahead_log_is_on(archive_path: Path) -> None:
    connection = open_archive(archive_path)
    mode = connection.execute("PRAGMA journal_mode").fetchone()[0]
    assert mode.lower() == "wal"


def test_the_busy_timeout_is_set(archive_path: Path) -> None:
    connection = open_archive(archive_path)
    assert connection.execute("PRAGMA busy_timeout").fetchone()[0] == 5000


def test_a_record_key_is_unique(archive_path: Path) -> None:
    connection = open_archive(archive_path)
    insert = (
        "INSERT INTO records (key, site, unit, klass, stream, observed_ns, message, stored_ns)"
        " VALUES (?, 'US-TX-0417', 'pump-bridge', 'standard', 'stdout', 1, 'x', 1)"
    )
    connection.execute(insert, ("abc",))
    with pytest.raises(sqlite3.IntegrityError):
        connection.execute(insert, ("abc",))


def test_a_missing_version_row_reads_as_zero(tmp_path: Path) -> None:
    target = tmp_path / "bare.sqlite3"
    connection = connect(target)
    assert read_version(connection) == 0


def test_a_non_numeric_version_is_refused(archive_path: Path) -> None:
    connection = open_archive(archive_path)
    connection.execute("UPDATE meta SET value = 'one' WHERE name = 'schema_version'")
    with pytest.raises(StanchionError, match="version is not a number"):
        read_version(connection)


def test_an_archive_from_the_future_is_refused(archive_path: Path) -> None:
    connection = open_archive(archive_path)
    connection.execute("UPDATE meta SET value = '99' WHERE name = 'schema_version'")
    connection.close()
    with pytest.raises(StanchionError, match="newer stanchion"):
        open_archive(archive_path)


def test_a_file_that_is_not_a_database_is_refused(tmp_path: Path) -> None:
    target = tmp_path / "junk.sqlite3"
    target.write_bytes(b"this is not a database" * 10)
    with pytest.raises(StanchionError):
        open_archive(target)


def test_read_only_refuses_a_missing_file(archive_path: Path) -> None:
    with pytest.raises(StanchionError, match="does not exist"):
        open_archive(archive_path, read_only=True)


def test_read_only_can_read(archive_path: Path) -> None:
    open_archive(archive_path).close()
    connection = open_archive(archive_path, read_only=True)
    assert read_version(connection) == SCHEMA_VERSION


def test_read_only_cannot_write(archive_path: Path) -> None:
    open_archive(archive_path).close()
    connection = open_archive(archive_path, read_only=True)
    with pytest.raises(sqlite3.OperationalError):
        connection.execute("DELETE FROM records")


def test_create_is_idempotent(archive_path: Path) -> None:
    connection = open_archive(archive_path)
    create(connection)
    create(connection)
    assert read_version(connection) == SCHEMA_VERSION


def test_describe_reports_an_empty_archive(archive_path: Path) -> None:
    connection = open_archive(archive_path)
    info = describe(connection, archive_path)
    assert info.records == 0
    assert info.empty is True
    assert info.version == SCHEMA_VERSION


def test_describe_counts_the_records(archive_path: Path) -> None:
    connection = open_archive(archive_path)
    connection.execute(
        "INSERT INTO records (key, site, unit, klass, stream, observed_ns, message, stored_ns)"
        " VALUES ('k', 'US-TX-0417', 'pump-bridge', 'standard', 'stdout', 1, 'x', 1)"
    )
    assert describe(connection, archive_path).records == 1


def test_a_hold_can_be_recorded(archive_path: Path) -> None:
    connection = open_archive(archive_path)
    connection.execute(
        "INSERT INTO holds (name, reason, placed_ns) VALUES ('case-41', 'dispute', 1)"
    )
    row = connection.execute("SELECT * FROM holds").fetchone()
    assert row["name"] == "case-41"
    assert row["until_ns"] is None
