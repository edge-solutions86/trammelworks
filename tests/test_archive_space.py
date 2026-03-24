from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from stanchion.archive.repository import Repository
from stanchion.archive.retention import Hold
from stanchion.archive.schema import open_archive
from stanchion.archive.space import (
    DEFAULT_MIN_FRACTION,
    Footprint,
    Trimmer,
    TrimReport,
    checkpoint,
    file_size,
    measure,
    room_to_vacuum,
    vacuum,
    worth_vacuuming,
)
from stanchion.errors import RetentionError
from stanchion.records import LogRecord, Stream
from stanchion.spool.priority import PriorityTable
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-09-01T12:00:00Z")
TABLE = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})
DAY_NS = 24 * 60 * 60 * 1_000_000_000


@pytest.fixture
def path(tmp_path: Path) -> Path:
    return tmp_path / "archive" / "records.sqlite3"


@pytest.fixture
def connection(path: Path) -> sqlite3.Connection:
    return open_archive(path)


@pytest.fixture
def repo(connection: sqlite3.Connection) -> Repository:
    return Repository(connection=connection, table=TABLE)


def fill(repo: Repository, unit: str, count: int, day: int = 0) -> None:
    repo.add_all(
        [
            LogRecord(
                site="US-TX-0417",
                unit=unit,
                stream=Stream.STDOUT,
                observed_at=NanoTime(NOW.epoch_ns - (day * DAY_NS) + index),
                message=f"{unit} {day} {index} " + "x" * 300,
            )
            for index in range(count)
        ],
        NOW,
    )


def trimmer(connection: sqlite3.Connection, path: Path, cap: int, **over: object) -> Trimmer:
    settings: dict[str, object] = {
        "connection": connection,
        "path": path,
        "cap_bytes": cap,
        "table": TABLE,
        "batch": 200,
    }
    settings.update(over)
    return Trimmer(**settings)  # type: ignore[arg-type]


def counts(connection: sqlite3.Connection) -> dict[str, int]:
    rows = connection.execute("SELECT klass, COUNT(*) AS n FROM records GROUP BY klass")
    return {str(row["klass"]): int(row["n"]) for row in rows}


def test_an_empty_footprint_is_all_zero() -> None:
    found = Footprint()
    assert found.total == 0
    assert found.used == 0
    assert found.free_fraction == 0.0


def test_a_footprint_counts_the_wal_as_well() -> None:
    found = Footprint(main_bytes=1000, wal_bytes=250, page_size=100, page_count=10)
    assert found.total == 1250


def test_the_used_size_leaves_out_the_free_pages() -> None:
    found = Footprint(main_bytes=1000, page_size=100, page_count=10, free_pages=4)
    assert found.used == 600


def test_the_used_size_never_goes_negative() -> None:
    found = Footprint(main_bytes=1000, page_size=100, page_count=2, free_pages=9)
    assert found.used == 0


def test_the_used_size_leaves_the_log_out() -> None:
    # Only a checkpoint returns the log, so a trim measured against it would
    # never finish.
    found = Footprint(main_bytes=1000, wal_bytes=9000, page_size=100, page_count=10)
    assert found.used == 1000


def test_a_checkpoint_folds_the_log_back_in(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "price-sign", 500)
    checkpoint(connection)
    assert measure(connection, path).wal_bytes == 0


def test_a_checkpoint_on_a_closed_archive_is_refused(connection: sqlite3.Connection) -> None:
    connection.close()
    with pytest.raises(RetentionError, match="will not checkpoint"):
        checkpoint(connection)


def test_what_a_vacuum_would_give_back_is_the_free_pages() -> None:
    found = Footprint(main_bytes=1000, page_size=100, page_count=10, free_pages=4)
    assert found.reclaimable == 400
    assert found.free_fraction == pytest.approx(0.4)


def test_a_footprint_reads_as_one_line() -> None:
    found = Footprint(main_bytes=1000, page_size=100, page_count=10, free_pages=4)
    assert str(found) == "1000 bytes, 400 reclaimable (4 of 10 pages free)"


def test_a_missing_file_measures_as_nothing(tmp_path: Path) -> None:
    assert file_size(tmp_path / "absent") == 0


def test_measuring_a_real_archive_reports_pages(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "price-sign", 200)
    found = measure(connection, path)
    assert found.page_size > 0
    assert found.page_count > 0
    assert found.total > 0


def test_measuring_a_closed_archive_is_refused(connection: sqlite3.Connection, path: Path) -> None:
    connection.close()
    with pytest.raises(RetentionError, match="will not report its size"):
        measure(connection, path)


def test_nothing_free_is_not_worth_vacuuming() -> None:
    assert worth_vacuuming(Footprint(main_bytes=1000, page_size=100, page_count=10)) is False


def test_a_big_free_portion_is_worth_vacuuming() -> None:
    found = Footprint(main_bytes=1000, page_size=100, page_count=10, free_pages=5)
    assert worth_vacuuming(found, min_reclaim=10_000_000) is True


def test_a_small_free_portion_is_not() -> None:
    found = Footprint(main_bytes=100_000, page_size=100, page_count=1000, free_pages=1)
    assert worth_vacuuming(found, min_reclaim=10_000_000) is False


def test_the_default_fraction_is_the_one_documented() -> None:
    found = Footprint(main_bytes=1000, page_size=100, page_count=10, free_pages=2)
    assert found.free_fraction == pytest.approx(DEFAULT_MIN_FRACTION)


def test_a_vacuum_gives_the_file_back(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "price-sign", 2000)
    with connection:
        connection.execute("DELETE FROM records")
    before = file_size(path)
    freed = vacuum(connection, path)
    assert freed > 0
    assert file_size(path) < before


def test_a_vacuum_on_a_full_archive_gives_little_back(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "price-sign", 500)
    assert vacuum(connection, path) < file_size(path)


def test_a_vacuum_keeps_the_records(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "price-sign", 300)
    vacuum(connection, path)
    assert repo.count() == 300


def test_a_vacuum_on_a_closed_archive_is_refused(
    connection: sqlite3.Connection, path: Path
) -> None:
    connection.close()
    with pytest.raises(RetentionError, match="vacuum failed"):
        vacuum(connection, path)


def test_a_disk_with_room_allows_the_vacuum(tmp_path: Path) -> None:
    assert room_to_vacuum(tmp_path / "records.sqlite3", 1024) is True


def test_a_disk_that_cannot_hold_the_copy_refuses(tmp_path: Path) -> None:
    assert room_to_vacuum(tmp_path / "records.sqlite3", 1 << 62) is False


def test_a_cap_of_nothing_is_refused(connection: sqlite3.Connection, path: Path) -> None:
    with pytest.raises(RetentionError, match="cap must be positive"):
        trimmer(connection, path, 0)


def test_a_batch_of_nothing_is_refused(connection: sqlite3.Connection, path: Path) -> None:
    with pytest.raises(RetentionError, match="at least one"):
        trimmer(connection, path, 1024, batch=0)


def test_an_archive_under_its_cap_is_left_alone(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "price-sign", 50)
    report = trimmer(connection, path, 100 * 1024 * 1024).run([], NOW)
    assert report.idle
    assert report.stopped == "already under the cap"


def test_an_archive_over_its_cap_is_trimmed(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "price-sign", 2000)
    checkpoint(connection)
    before = repo.count()
    report = trimmer(connection, path, 100_000).run([], NOW)
    assert report.deleted > 0
    assert repo.count() < before


def test_trimming_stops_once_it_is_under_the_cap(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "price-sign", 2000)
    checkpoint(connection)
    built = trimmer(connection, path, 200_000)
    built.run([], NOW)
    assert built.over_by() == 0
    assert repo.count() > 0


def test_the_least_important_class_goes_first(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "price-sign", 1200)
    fill(repo, "pump-bridge", 1200)
    checkpoint(connection)
    cap = measure(connection, path).used - 100_000
    report = trimmer(connection, path, cap).run([], NOW)
    assert report.by_class.get("chatter", 0) > 0
    assert "standard" not in report.by_class


def test_a_protected_class_is_never_trimmed(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "payment-kernel", 3000)
    checkpoint(connection)
    report = trimmer(connection, path, 100_000).run([], NOW)
    assert counts(connection).get("payment") == 3000
    assert report.stopped == "nothing left that may be deleted"


def test_the_oldest_records_go_first(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "price-sign", 1000, day=30)
    fill(repo, "price-sign", 1000, day=1)
    checkpoint(connection)
    trimmer(connection, path, 250_000).run([], NOW)
    row = connection.execute("SELECT MIN(observed_ns) AS oldest FROM records").fetchone()
    assert int(row["oldest"]) > NOW.epoch_ns - (2 * DAY_NS)


def test_a_held_record_is_not_trimmed(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "price-sign", 2000, day=30)
    checkpoint(connection)
    hold = Hold(
        name="dispute-88213",
        reason="chargeback",
        placed_at=NOW,
        covers_from=NanoTime(NOW.epoch_ns - (60 * DAY_NS)),
        covers_until=NOW,
    )
    report = trimmer(connection, path, 100_000).run([hold], NOW)
    assert report.deleted == 0
    assert report.held > 0


def test_a_hold_that_covers_nothing_does_not_stop_the_trim(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "price-sign", 2000, day=1)
    checkpoint(connection)
    hold = Hold(
        name="dispute-11",
        reason="chargeback",
        placed_at=NOW,
        covers_from=NanoTime(NOW.epoch_ns - (400 * DAY_NS)),
        covers_until=NanoTime(NOW.epoch_ns - (300 * DAY_NS)),
    )
    assert trimmer(connection, path, 100_000).run([hold], NOW).deleted > 0


def test_a_trim_report_reads_as_one_line() -> None:
    report = TrimReport(examined=10, deleted=8, held=2)
    assert str(report) == "trimmed 8 of 10 examined, 2 held"


def test_a_trim_report_says_why_it_stopped() -> None:
    report = TrimReport(stopped="nothing left that may be deleted")
    assert "stopped: nothing left" in str(report)


def test_the_trim_order_leaves_protected_classes_out(
    connection: sqlite3.Connection, path: Path
) -> None:
    assert "payment" not in trimmer(connection, path, 1024).order()


def test_the_trim_order_starts_with_the_cheapest(
    connection: sqlite3.Connection, path: Path
) -> None:
    assert trimmer(connection, path, 1024).order()[0] == "chatter"


def test_the_next_class_up_is_only_touched_once_the_first_is_gone(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "price-sign", 1200)
    fill(repo, "pump-bridge", 1200)
    checkpoint(connection)
    report = trimmer(connection, path, 100_000).run([], NOW)
    assert report.by_class["standard"] > 0
    assert report.by_class["chatter"] == 1200


def test_a_vacuum_after_a_trim_hands_the_card_the_space(
    connection: sqlite3.Connection, repo: Repository, path: Path
) -> None:
    fill(repo, "price-sign", 3000)
    checkpoint(connection)
    before = file_size(path)
    trimmer(connection, path, 200_000).run([], NOW)
    assert file_size(path) == before
    vacuum(connection, path)
    assert file_size(path) < before
