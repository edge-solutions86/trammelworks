from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from stanchion.archive.repository import Repository, StoredRecord
from stanchion.archive.schema import open_archive
from stanchion.errors import StanchionError
from stanchion.records import LogRecord, Stream
from stanchion.spool.priority import PriorityTable
from stanchion.timestamps import NanoTime

SITE = "US-TX-0417"
TABLE = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})
NOW = NanoTime.parse("2025-03-04T12:00:00Z")


def at(raw: str) -> NanoTime:
    return NanoTime.parse(raw)


def record(**over: object) -> LogRecord:
    base: dict[str, object] = {
        "site": SITE,
        "unit": "pump-bridge",
        "stream": Stream.STDOUT,
        "observed_at": at("2025-03-04T12:00:00Z"),
        "message": "nozzle 1 lifted",
    }
    base.update(over)
    return LogRecord(**base)  # type: ignore[arg-type]


@pytest.fixture
def connection(tmp_path: Path) -> sqlite3.Connection:
    return open_archive(tmp_path / "archive" / "records.sqlite3")


@pytest.fixture
def repo(connection: sqlite3.Connection) -> Repository:
    return Repository(connection=connection, table=TABLE)


def test_a_record_is_stored(repo: Repository) -> None:
    assert repo.add(record(), NOW) is True
    assert repo.count() == 1


def test_the_same_record_twice_is_stored_once(repo: Repository) -> None:
    repo.add(record(), NOW)
    assert repo.add(record(), NOW) is False
    assert repo.count() == 1
    assert repo.duplicates == 1


def test_two_different_records_are_both_stored(repo: Repository) -> None:
    repo.add(record(message="one"), NOW)
    repo.add(record(message="two"), NOW)
    assert repo.count() == 2


def test_the_class_comes_from_the_priority_table(repo: Repository) -> None:
    repo.add(record(unit="payment-kernel"), NOW)
    assert repo.counts_by_class() == {"payment": 1}


def test_an_unassigned_unit_lands_in_the_default_class(repo: Repository) -> None:
    repo.add(record(), NOW)
    assert repo.counts_by_class() == {"standard": 1}


def test_a_record_reads_back_whole(repo: Repository) -> None:
    repo.add(record(labels={"lane": "3"}), NOW)
    found = repo.between(at("2025-03-04T00:00:00Z"), at("2025-03-05T00:00:00Z"))
    assert len(found) == 1
    assert found[0].message == "nozzle 1 lifted"
    assert found[0].labels == {"lane": "3"}
    assert found[0].unit == "pump-bridge"


def test_a_stored_record_can_become_a_log_record_again(repo: Repository) -> None:
    repo.add(record(labels={"lane": "3"}), NOW)
    found = repo.between(at("2025-03-04T00:00:00Z"), at("2025-03-05T00:00:00Z"))[0]
    rebuilt = found.to_record()
    assert rebuilt.site == SITE
    assert rebuilt.labels == {"lane": "3"}


def test_the_nanosecond_precision_survives(repo: Repository) -> None:
    repo.add(record(observed_at=at("2025-03-04T12:00:00.000000042Z")), NOW)
    found = repo.between(at("2025-03-04T00:00:00Z"), at("2025-03-05T00:00:00Z"))[0]
    assert found.observed_at.nanos_in_second == 42


def test_a_corrected_time_is_kept_beside_the_raw_one(repo: Repository) -> None:
    repo.add(
        record(
            observed_at=at("2025-03-04T11:59:53Z"),
            corrected_at=at("2025-03-04T12:00:00Z"),
            clock_confidence="interpolated",
        ),
        NOW,
    )
    found = repo.between(at("2025-03-04T00:00:00Z"), at("2025-03-05T00:00:00Z"))[0]
    assert found.observed_at == at("2025-03-04T11:59:53Z")
    assert found.corrected_at == at("2025-03-04T12:00:00Z")
    assert found.confidence == "interpolated"


def test_the_window_is_read_against_the_corrected_time(repo: Repository) -> None:
    repo.add(
        record(
            observed_at=at("2025-03-04T10:00:00Z"),
            corrected_at=at("2025-03-04T12:00:00Z"),
        ),
        NOW,
    )
    inside = repo.between(at("2025-03-04T11:00:00Z"), at("2025-03-04T13:00:00Z"))
    outside = repo.between(at("2025-03-04T09:00:00Z"), at("2025-03-04T11:00:00Z"))
    assert len(inside) == 1
    assert outside == []


def test_records_come_back_oldest_first(repo: Repository) -> None:
    for hour in (14, 12, 13):
        repo.add(record(observed_at=at(f"2025-03-04T{hour}:00:00Z")), NOW)
    found = repo.between(at("2025-03-04T00:00:00Z"), at("2025-03-05T00:00:00Z"))
    assert [item.observed_at.to_datetime().hour for item in found] == [12, 13, 14]


def test_the_window_boundaries_are_inclusive(repo: Repository) -> None:
    repo.add(record(observed_at=at("2025-03-04T12:00:00Z")), NOW)
    assert len(repo.between(at("2025-03-04T12:00:00Z"), at("2025-03-04T12:00:00Z"))) == 1


def test_a_backwards_window_is_refused(repo: Repository) -> None:
    with pytest.raises(StanchionError, match="ends before it starts"):
        repo.between(at("2025-03-05T00:00:00Z"), at("2025-03-04T00:00:00Z"))


@pytest.mark.parametrize("limit", [0, -1, 200_000])
def test_an_out_of_range_limit_is_refused(repo: Repository, limit: int) -> None:
    with pytest.raises(StanchionError, match="limit is out of range"):
        repo.between(at("2025-03-04T00:00:00Z"), at("2025-03-05T00:00:00Z"), limit=limit)


def test_a_negative_offset_is_refused(repo: Repository) -> None:
    with pytest.raises(StanchionError, match="offset cannot be negative"):
        repo.between(at("2025-03-04T00:00:00Z"), at("2025-03-05T00:00:00Z"), offset=-1)


def test_the_limit_bounds_the_answer(repo: Repository) -> None:
    for index in range(5):
        repo.add(record(message=f"line {index}"), NOW)
    assert len(repo.between(at("2025-03-04T00:00:00Z"), at("2025-03-05T00:00:00Z"), limit=2)) == 2


def test_paging_walks_the_whole_window(repo: Repository) -> None:
    for index in range(5):
        repo.add(record(observed_at=at(f"2025-03-04T12:00:0{index}Z")), NOW)
    first = repo.between(at("2025-03-04T00:00:00Z"), at("2025-03-05T00:00:00Z"), limit=2)
    second = repo.between(at("2025-03-04T00:00:00Z"), at("2025-03-05T00:00:00Z"), limit=2, offset=2)
    assert {item.key for item in first} & {item.key for item in second} == set()


def test_filtering_by_unit(repo: Repository) -> None:
    repo.add(record(unit="pump-bridge"), NOW)
    repo.add(record(unit="tank-gauge"), NOW)
    found = repo.between(at("2025-03-04T00:00:00Z"), at("2025-03-05T00:00:00Z"), unit="tank-gauge")
    assert [item.unit for item in found] == ["tank-gauge"]


def test_filtering_by_class(repo: Repository) -> None:
    repo.add(record(unit="payment-kernel"), NOW)
    repo.add(record(unit="price-sign"), NOW)
    found = repo.between(at("2025-03-04T00:00:00Z"), at("2025-03-05T00:00:00Z"), klass="payment")
    assert [item.klass for item in found] == ["payment"]


def test_the_units_are_listed(repo: Repository) -> None:
    repo.add(record(unit="tank-gauge"), NOW)
    repo.add(record(unit="pump-bridge"), NOW)
    assert repo.units() == ("pump-bridge", "tank-gauge")


def test_an_empty_archive_has_no_span(repo: Repository) -> None:
    assert repo.span() is None


def test_the_span_covers_the_records(repo: Repository) -> None:
    repo.add(record(observed_at=at("2025-03-04T10:00:00Z")), NOW)
    repo.add(record(observed_at=at("2025-03-04T14:00:00Z")), NOW)
    span = repo.span()
    assert span is not None
    assert span[0] == at("2025-03-04T10:00:00Z")
    assert span[1] == at("2025-03-04T14:00:00Z")


def test_add_all_reports_how_many_were_new(repo: Repository) -> None:
    batch = [record(message=f"line {index}") for index in range(3)]
    assert repo.add_all(batch, NOW) == 3
    assert repo.add_all(batch, NOW) == 0


def test_the_write_counter_follows(repo: Repository) -> None:
    repo.add(record(), NOW)
    repo.add(record(), NOW)
    assert repo.written == 1
    assert repo.duplicates == 1


def test_a_row_with_broken_labels_reads_as_empty(repo: Repository) -> None:
    repo.add(record(), NOW)
    repo.connection.execute("UPDATE records SET labels = 'not json'")
    found = repo.between(at("2025-03-04T00:00:00Z"), at("2025-03-05T00:00:00Z"))
    assert found[0].labels == {}


def test_best_at_prefers_the_correction() -> None:
    stored = StoredRecord(
        key="k",
        site=SITE,
        unit="pump-bridge",
        klass="standard",
        stream=Stream.STDOUT,
        observed_at=at("2025-03-04T11:00:00Z"),
        corrected_at=at("2025-03-04T12:00:00Z"),
        confidence="measured",
        message="x",
        labels={},
        stored_at=NOW,
    )
    assert stored.best_at == at("2025-03-04T12:00:00Z")
