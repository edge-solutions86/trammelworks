from __future__ import annotations

import sqlite3
from datetime import timedelta
from pathlib import Path

import pytest

from stanchion.archive.repository import Repository
from stanchion.archive.retention import Hold, RetentionPolicy
from stanchion.archive.schema import open_archive
from stanchion.archive.sweeper import HoldStore, Sweeper
from stanchion.errors import RetentionError
from stanchion.records import LogRecord, Stream
from stanchion.spool.priority import PriorityTable
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-09-01T12:00:00Z")
TABLE = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})
POLICY = RetentionPolicy(
    windows={
        "payment": timedelta(days=180),
        "standard": timedelta(days=30),
        "chatter": timedelta(days=7),
    }
)


def at(raw: str) -> NanoTime:
    return NanoTime.parse(raw)


@pytest.fixture
def connection(tmp_path: Path) -> sqlite3.Connection:
    return open_archive(tmp_path / "archive" / "records.sqlite3")


@pytest.fixture
def repo(connection: sqlite3.Connection) -> Repository:
    return Repository(connection=connection, table=TABLE)


def add(repo: Repository, unit: str, when: str, message: str = "line") -> None:
    repo.add(
        LogRecord(
            site="US-TX-0417",
            unit=unit,
            stream=Stream.STDOUT,
            observed_at=at(when),
            message=f"{message} {unit} {when}",
        ),
        NOW,
    )


def sweeper(connection: sqlite3.Connection, **over: object) -> Sweeper:
    settings: dict[str, object] = {"connection": connection, "policy": POLICY}
    settings.update(over)
    return Sweeper(**settings)  # type: ignore[arg-type]


def test_a_max_of_zero_is_refused(connection: sqlite3.Connection) -> None:
    with pytest.raises(RetentionError, match="at least one"):
        sweeper(connection, max_deletes=0)


def test_an_empty_archive_sweeps_nothing(connection: sqlite3.Connection) -> None:
    report = sweeper(connection).sweep(NOW)
    assert report.deleted == 0
    assert report.examined == 0


def test_a_recent_record_survives(connection: sqlite3.Connection, repo: Repository) -> None:
    add(repo, "price-sign", "2025-08-30T12:00:00Z")
    sweeper(connection).sweep(NOW)
    assert repo.count() == 1


def test_an_expired_chatter_record_goes(connection: sqlite3.Connection, repo: Repository) -> None:
    add(repo, "price-sign", "2025-08-01T12:00:00Z")
    report = sweeper(connection).sweep(NOW)
    assert report.deleted == 1
    assert repo.count() == 0


def test_a_payment_record_of_the_same_age_stays(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    add(repo, "price-sign", "2025-08-01T12:00:00Z")
    add(repo, "payment-kernel", "2025-08-01T12:00:00Z")
    sweeper(connection).sweep(NOW)
    assert repo.counts_by_class() == {"payment": 1}


def test_a_payment_record_past_its_window_goes(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    add(repo, "payment-kernel", "2024-01-01T12:00:00Z")
    sweeper(connection).sweep(NOW)
    assert repo.count() == 0


def test_the_report_counts_by_class(connection: sqlite3.Connection, repo: Repository) -> None:
    add(repo, "price-sign", "2025-08-01T12:00:00Z", message="a")
    add(repo, "price-sign", "2025-08-02T12:00:00Z", message="b")
    report = sweeper(connection).sweep(NOW)
    assert report.by_class == {"chatter": 2}


def test_the_report_reads_as_one_line(connection: sqlite3.Connection, repo: Repository) -> None:
    add(repo, "price-sign", "2025-08-01T12:00:00Z")
    assert "deleted 1" in str(sweeper(connection).sweep(NOW))


def test_a_hold_stops_a_deletion(connection: sqlite3.Connection, repo: Repository) -> None:
    add(repo, "price-sign", "2025-08-01T12:00:00Z")
    HoldStore(connection=connection).place(Hold(name="case-41", reason="dispute", placed_at=NOW))
    report = sweeper(connection).sweep(NOW)
    assert report.deleted == 0
    assert report.held == 1
    assert repo.count() == 1


def test_lifting_a_hold_lets_the_sweep_through(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    add(repo, "price-sign", "2025-08-01T12:00:00Z")
    holds = HoldStore(connection=connection)
    holds.place(Hold(name="case-41", reason="dispute", placed_at=NOW))
    sweeper(connection).sweep(NOW)
    assert holds.lift("case-41") is True
    sweeper(connection).sweep(NOW)
    assert repo.count() == 0


def test_lifting_an_absent_hold_reports_it(connection: sqlite3.Connection) -> None:
    assert HoldStore(connection=connection).lift("case-41") is False


def test_a_windowed_hold_saves_only_its_window(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    add(repo, "price-sign", "2025-08-01T12:00:00Z", message="inside")
    add(repo, "price-sign", "2025-06-01T12:00:00Z", message="outside")
    HoldStore(connection=connection).place(
        Hold(
            name="case-41",
            reason="dispute",
            placed_at=NOW,
            covers_from=at("2025-07-15T00:00:00Z"),
            covers_until=at("2025-08-15T00:00:00Z"),
        )
    )
    sweeper(connection).sweep(NOW)
    assert repo.count() == 1


def test_a_hold_is_read_back(connection: sqlite3.Connection) -> None:
    holds = HoldStore(connection=connection)
    holds.place(
        Hold(
            name="case-41",
            reason="chargeback 8821",
            placed_at=NOW,
            covers_from=at("2025-07-01T00:00:00Z"),
        )
    )
    found = holds.get("case-41")
    assert found is not None
    assert found.reason == "chargeback 8821"
    assert found.covers_from == at("2025-07-01T00:00:00Z")
    assert found.covers_until is None


def test_placing_a_hold_twice_replaces_it(connection: sqlite3.Connection) -> None:
    holds = HoldStore(connection=connection)
    holds.place(Hold(name="case-41", reason="first", placed_at=NOW))
    holds.place(Hold(name="case-41", reason="second", placed_at=NOW))
    assert len(holds.all()) == 1
    found = holds.get("case-41")
    assert found is not None
    assert found.reason == "second"


def test_an_unknown_hold_is_none(connection: sqlite3.Connection) -> None:
    assert HoldStore(connection=connection).get("case-41") is None


def test_holds_come_back_in_the_order_they_were_placed(
    connection: sqlite3.Connection,
) -> None:
    holds = HoldStore(connection=connection)
    holds.place(Hold(name="second", reason="b", placed_at=NOW.shifted(1)))
    holds.place(Hold(name="first", reason="a", placed_at=NOW))
    assert [hold.name for hold in holds.all()] == ["first", "second"]


def test_the_sweep_is_capped(connection: sqlite3.Connection, repo: Repository) -> None:
    for index in range(6):
        add(repo, "price-sign", "2025-08-01T12:00:00Z", message=f"line {index}")
    report = sweeper(connection, max_deletes=2).sweep(NOW)
    assert report.deleted == 2
    assert report.capped is True
    assert repo.count() == 4


def test_sweeping_until_clear_finishes_the_job(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    for index in range(6):
        add(repo, "price-sign", "2025-08-01T12:00:00Z", message=f"line {index}")
    report = sweeper(connection, max_deletes=2).sweep_until_clear(NOW)
    assert report.deleted == 6
    assert repo.count() == 0


def test_sweeping_until_clear_stops_when_nothing_moves(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    add(repo, "price-sign", "2025-08-30T12:00:00Z")
    report = sweeper(connection, max_deletes=1).sweep_until_clear(NOW)
    assert report.deleted == 0


def test_the_candidate_query_ignores_anything_too_new(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    add(repo, "price-sign", "2025-08-30T12:00:00Z")
    assert sweeper(connection).candidates(NOW, 100) == []


def test_the_candidate_query_finds_the_old_ones(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    add(repo, "price-sign", "2024-01-01T12:00:00Z")
    assert len(sweeper(connection).candidates(NOW, 100)) == 1


def test_the_horizon_follows_the_shortest_window_not_the_longest(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    # A chatter record 31 days old is past its 7 day window. A horizon taken
    # from the 180 day payment window would never even look at it.
    add(repo, "price-sign", "2025-08-01T12:00:00Z")
    assert len(sweeper(connection).candidates(NOW, 100)) == 1
