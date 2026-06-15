from __future__ import annotations

import sqlite3
from collections.abc import Iterator
from pathlib import Path

import pytest

from stanchion.archive.repository import DEFAULT_BATCH, Repository, _chunks
from stanchion.archive.schema import open_archive
from stanchion.errors import StanchionError
from stanchion.records import LogRecord, Stream
from stanchion.spool.priority import PriorityTable
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-06-10T12:00:00Z")
TABLE = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})


@pytest.fixture
def connection(tmp_path: Path) -> sqlite3.Connection:
    return open_archive(tmp_path / "archive" / "records.sqlite3")


@pytest.fixture
def repo(connection: sqlite3.Connection) -> Repository:
    return Repository(connection=connection, table=TABLE)


def record(index: int, unit: str = "pump-bridge") -> LogRecord:
    return LogRecord(
        site="US-TX-0417",
        unit=unit,
        stream=Stream.STDOUT,
        observed_at=NanoTime(NOW.epoch_ns + index),
        message=f"line {index:05d}",
    )


def many(count: int, unit: str = "pump-bridge") -> list[LogRecord]:
    return [record(index, unit) for index in range(count)]


def test_chunks_of_an_empty_run_are_none() -> None:
    assert list(_chunks([], 10)) == []


def test_chunks_come_out_the_size_asked_for() -> None:
    found = list(_chunks(many(10), 4))
    assert [len(chunk) for chunk in found] == [4, 4, 2]


def test_a_short_run_is_one_chunk() -> None:
    assert len(list(_chunks(many(3), 500))) == 1


def test_chunks_keep_the_order() -> None:
    found = [item for chunk in _chunks(many(7), 3) for item in chunk]
    assert [item.message for item in found] == [item.message for item in many(7)]


def test_chunks_do_not_pull_the_whole_run_in() -> None:
    """A backfill hands this a generator over a day of records."""
    pulled = 0

    def stream() -> Iterator[LogRecord]:
        nonlocal pulled
        for item in many(20):
            pulled += 1
            yield item

    walker = _chunks(stream(), 5)
    next(walker)
    assert pulled == 5


def test_a_batch_of_nothing_is_refused(connection: sqlite3.Connection) -> None:
    with pytest.raises(StanchionError, match="at least one"):
        Repository(connection=connection, batch=0)


def test_the_default_batch_is_the_documented_one() -> None:
    assert DEFAULT_BATCH == 500


def test_one_record_still_stores(repo: Repository) -> None:
    assert repo.add(record(1), NOW) is True
    assert repo.count() == 1


def test_the_same_record_twice_is_not_new(repo: Repository) -> None:
    repo.add(record(1), NOW)
    assert repo.add(record(1), NOW) is False
    assert repo.count() == 1


def test_many_records_all_land(repo: Repository) -> None:
    assert repo.add_all(many(1200), NOW) == 1200
    assert repo.count() == 1200


def test_many_records_land_across_several_chunks(connection: sqlite3.Connection) -> None:
    repo = Repository(connection=connection, table=TABLE, batch=7)
    assert repo.add_all(many(50), NOW) == 50
    assert repo.count() == 50


def test_replaying_a_batch_adds_nothing(repo: Repository) -> None:
    repo.add_all(many(300), NOW)
    assert repo.add_all(many(300), NOW) == 0
    assert repo.count() == 300


def test_a_half_overlapping_batch_adds_only_the_new_part(repo: Repository) -> None:
    repo.add_all(many(200), NOW)
    again = [record(index) for index in range(100, 300)]
    assert repo.add_all(again, NOW) == 100
    assert repo.count() == 300


def test_the_counters_add_up(repo: Repository) -> None:
    repo.add_all(many(100), NOW)
    repo.add_all(many(100), NOW)
    assert repo.written == 100
    assert repo.duplicates == 100


def test_a_duplicate_inside_one_batch_is_counted_once(repo: Repository) -> None:
    batch = [*many(5), *many(5)]
    assert repo.add_all(batch, NOW) == 5
    assert repo.duplicates == 5


def test_the_records_keep_their_class(repo: Repository) -> None:
    repo.add_all(many(10, "payment-kernel"), NOW)
    repo.add_all(many(10, "price-sign"), NOW)
    assert repo.counts_by_class() == {"payment": 10, "chatter": 10}


def test_the_records_keep_their_content(repo: Repository) -> None:
    repo.add_all(many(5), NOW)
    found = repo.between(NanoTime(0), NanoTime(NOW.epoch_ns + 1000))
    assert [item.message for item in found] == [f"line {index:05d}" for index in range(5)]


def test_a_batch_from_a_generator_stores(repo: Repository) -> None:
    assert repo.add_all((item for item in many(40)), NOW) == 40
    assert repo.count() == 40


def test_an_empty_batch_stores_nothing(repo: Repository) -> None:
    assert repo.add_all([], NOW) == 0
    assert repo.written == 0
    assert repo.duplicates == 0


class CountingConnection:
    """A connection that keeps a tally of the transactions opened on it.

    A proxy rather than a monkeypatch, because ``sqlite3.Connection.commit``
    is read only. Everything the repository touches is forwarded.
    """

    def __init__(self, inner: sqlite3.Connection) -> None:
        self.inner = inner
        self.transactions = 0

    def __enter__(self) -> sqlite3.Connection:
        self.transactions += 1
        return self.inner.__enter__()

    def __exit__(self, *args: object) -> None:
        self.inner.__exit__(*args)

    def __getattr__(self, name: str) -> object:
        return getattr(self.inner, name)


def test_a_batch_opens_far_fewer_transactions_than_it_has_records(
    connection: sqlite3.Connection,
) -> None:
    """The whole point: a transaction per record is what this replaced."""
    counting = CountingConnection(connection)
    repo = Repository(connection=counting, table=TABLE, batch=100)  # type: ignore[arg-type]
    repo.add_all(many(1000), NOW)
    assert repo.count() == 1000
    assert counting.transactions == 10


def test_one_record_opens_one_transaction(connection: sqlite3.Connection) -> None:
    counting = CountingConnection(connection)
    repo = Repository(connection=counting, table=TABLE)  # type: ignore[arg-type]
    repo.add(record(1), NOW)
    assert counting.transactions == 1
