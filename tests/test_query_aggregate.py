from __future__ import annotations

import sqlite3
from datetime import timedelta
from pathlib import Path

import pytest

from stanchion.archive.repository import Repository
from stanchion.archive.schema import open_archive
from stanchion.errors import QueryError
from stanchion.query.aggregate import (
    DEFAULT_BUCKET,
    Counter,
    Group,
    Tally,
    bucket_of,
    check_group,
    count_by,
    count_over_time,
    group_key,
)
from stanchion.query.ast import Query
from stanchion.query.parser import parse_query
from stanchion.records import LogRecord, Stream
from stanchion.spool.priority import PriorityTable
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-06-10T12:00:00Z")
HOUR_NS = 60 * 60 * 1_000_000_000
TABLE = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})


@pytest.fixture
def connection(tmp_path: Path) -> sqlite3.Connection:
    return open_archive(tmp_path / "archive" / "records.sqlite3")


@pytest.fixture
def repo(connection: sqlite3.Connection) -> Repository:
    return Repository(connection=connection, table=TABLE)


def add(
    repo: Repository,
    unit: str,
    hours_ago: int = 0,
    message: str = "line",
    labels: dict[str, str] | None = None,
    stream: Stream = Stream.STDOUT,
) -> None:
    repo.add(
        LogRecord(
            site="US-TX-0417",
            unit=unit,
            stream=stream,
            observed_at=NanoTime(NOW.epoch_ns - (hours_ago * HOUR_NS)),
            message=f"{message} {unit} {hours_ago}",
            labels=dict(labels or {}),
        ),
        NOW,
    )


def fill(repo: Repository) -> None:
    for index in range(6):
        add(repo, "pump-bridge", hours_ago=index)
    for index in range(3):
        add(repo, "price-sign", hours_ago=index, labels={"lane": "2"})
    add(repo, "payment-kernel", hours_ago=0, labels={"lane": "2"})


def q(text: str) -> Query:
    return parse_query(text, NOW)


def keys(tally: Tally) -> list[str]:
    return [item.key for item in tally.groups]


def as_map(tally: Tally) -> dict[str, int]:
    return {item.key: item.count for item in tally.groups}


def test_a_groupable_field_is_accepted() -> None:
    assert check_group("Unit") == "unit"


def test_grouping_by_message_is_refused() -> None:
    with pytest.raises(QueryError, match="cannot group by"):
        check_group("message")


def test_grouping_by_a_label_is_refused() -> None:
    with pytest.raises(QueryError, match="cannot group by"):
        check_group("label.lane")


def test_a_bucket_starts_where_the_origin_says() -> None:
    assert bucket_of(HOUR_NS + 5, HOUR_NS) == HOUR_NS


def test_an_instant_on_a_boundary_is_its_own_bucket() -> None:
    assert bucket_of(2 * HOUR_NS, HOUR_NS) == 2 * HOUR_NS


def test_an_instant_before_the_origin_floors_downwards() -> None:
    assert bucket_of(-1, HOUR_NS) == -HOUR_NS


def test_a_bucket_of_nothing_is_refused() -> None:
    with pytest.raises(QueryError, match="narrower than a nanosecond"):
        bucket_of(10, 0)


def test_a_counter_keeps_the_first_and_last_seen() -> None:
    counter = Counter()
    counter.add("pump-bridge", 500)
    counter.add("pump-bridge", 100)
    counter.add("pump-bridge", 900)
    group = counter.groups()[0]
    assert (group.first_ns, group.last_ns, group.count) == (100, 900, 3)


def test_a_counter_puts_the_biggest_group_first() -> None:
    counter = Counter()
    counter.add("a", 1)
    counter.add("b", 1)
    counter.add("b", 2)
    assert [item.key for item in counter.groups()] == ["b", "a"]


def test_equal_counts_come_back_in_key_order() -> None:
    counter = Counter()
    counter.add("zebra", 1)
    counter.add("apple", 1)
    assert [item.key for item in counter.groups()] == ["apple", "zebra"]


def test_a_group_knows_its_span() -> None:
    group = Group(key="pump-bridge", count=2, first_ns=100, last_ns=900)
    assert group.span_ns == 800
    assert group.first_at.epoch_ns == 100


def test_a_group_reads_as_one_line() -> None:
    assert str(Group(key="pump-bridge", count=4)) == "pump-bridge 4"


def test_a_tally_totals_its_groups() -> None:
    tally = Tally(groups=[Group("a", 3), Group("b", 2)])
    assert tally.total == 5
    assert tally.largest is not None
    assert tally.largest.key == "a"


def test_an_empty_tally_has_no_largest() -> None:
    assert Tally().largest is None


def test_a_tally_says_where_it_counted() -> None:
    assert "counted in the archive" in Tally(in_database=True).describe()
    assert "counted in memory" in Tally(in_database=False).describe()


def test_counting_by_unit(connection: sqlite3.Connection, repo: Repository) -> None:
    fill(repo)
    tally = count_by(connection, q(""), "unit")
    assert as_map(tally) == {"pump-bridge": 6, "price-sign": 3, "payment-kernel": 1}


def test_counting_by_class(connection: sqlite3.Connection, repo: Repository) -> None:
    fill(repo)
    assert as_map(count_by(connection, q(""), "class")) == {
        "standard": 6,
        "chatter": 3,
        "payment": 1,
    }


def test_counting_by_stream(connection: sqlite3.Connection, repo: Repository) -> None:
    add(repo, "pump-bridge", stream=Stream.STDERR)
    add(repo, "pump-bridge", hours_ago=1)
    assert as_map(count_by(connection, q(""), "stream")) == {
        "stderr": 1,
        "stdout": 1,
    }


def test_a_count_is_narrowed_by_the_filter(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    fill(repo)
    tally = count_by(connection, q("unit = pump-bridge"), "unit")
    assert as_map(tally) == {"pump-bridge": 6}


def test_a_filter_that_matches_nothing_gives_no_groups(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    fill(repo)
    assert count_by(connection, q("unit = nothing-here"), "unit").groups == []


def test_a_pushable_filter_counts_in_the_database(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    fill(repo)
    assert count_by(connection, q("unit = pump-bridge"), "unit").in_database is True


def test_a_label_filter_counts_in_memory(connection: sqlite3.Connection, repo: Repository) -> None:
    fill(repo)
    assert count_by(connection, q("label.lane = 2"), "unit").in_database is False


def test_a_label_filter_still_counts_the_right_records(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    fill(repo)
    tally = count_by(connection, q("label.lane = 2"), "unit")
    assert as_map(tally) == {"price-sign": 3, "payment-kernel": 1}


def test_the_residual_is_applied_before_anything_is_counted(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    fill(repo)
    tally = count_by(connection, q("label.lane = 2"), "unit")
    assert tally.post_filtered == 6
    assert tally.total == 4


def test_the_two_paths_agree(connection: sqlite3.Connection, repo: Repository) -> None:
    fill(repo)
    pushed = count_by(connection, q("unit in (price-sign, payment-kernel)"), "unit")
    residual = count_by(connection, q("label.lane = 2"), "unit")
    assert as_map(pushed) == as_map(residual)
    assert pushed.in_database is not residual.in_database


def test_a_null_and_an_empty_value_get_the_same_label() -> None:
    assert group_key(None) == group_key("") == "(none)"


def test_a_present_value_keeps_its_own_label() -> None:
    assert group_key("measured") == "measured"


def test_both_paths_label_a_missing_value_the_same(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    fill(repo)
    with connection:
        connection.execute("UPDATE records SET confidence = '' WHERE unit = 'price-sign'")
    pushed = count_by(connection, q("unit = price-sign"), "confidence")
    residual = count_by(connection, q("label.lane = 2"), "confidence")
    assert pushed.in_database is not residual.in_database
    assert "(none)" in as_map(pushed)
    assert "(none)" in as_map(residual)


def test_a_missing_value_groups_as_none(connection: sqlite3.Connection, repo: Repository) -> None:
    fill(repo)
    with connection:
        connection.execute("UPDATE records SET confidence = '' WHERE unit = 'price-sign'")
    assert as_map(count_by(connection, q(""), "confidence"))["(none)"] == 3


def test_counting_over_time_buckets(connection: sqlite3.Connection, repo: Repository) -> None:
    for index in range(5):
        add(repo, "pump-bridge", hours_ago=index)
    tally = count_over_time(connection, q(""), bucket=timedelta(hours=1))
    assert len(tally.groups) == 5
    assert tally.total == 5


def test_a_wider_bucket_gathers_more_together(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    for index in range(6):
        add(repo, "pump-bridge", hours_ago=index)
    tally = count_over_time(connection, q(""), bucket=timedelta(hours=6))
    assert len(tally.groups) <= 2
    assert tally.total == 6


def test_time_buckets_line_up_between_runs(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    for index in range(4):
        add(repo, "pump-bridge", hours_ago=index)
    first = keys(count_over_time(connection, q(""), bucket=timedelta(hours=1)))
    add(repo, "price-sign", hours_ago=9)
    second = keys(count_over_time(connection, q(""), bucket=timedelta(hours=1)))
    assert set(first) <= set(second)


def test_a_bucket_narrower_than_a_second_is_refused(connection: sqlite3.Connection) -> None:
    with pytest.raises(QueryError, match="at least a second"):
        count_over_time(connection, q(""), bucket=timedelta(milliseconds=5))


def test_the_default_bucket_is_an_hour() -> None:
    assert DEFAULT_BUCKET == timedelta(hours=1)


def test_counting_over_time_with_a_label_filter_counts_in_memory(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    fill(repo)
    tally = count_over_time(connection, q("label.lane = 2"), bucket=timedelta(hours=1))
    assert tally.in_database is False
    assert tally.total == 4


def test_a_time_count_and_a_field_count_see_the_same_records(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    fill(repo)
    by_unit = count_by(connection, q(""), "unit")
    over_time = count_over_time(connection, q(""), bucket=timedelta(days=1))
    assert by_unit.total == over_time.total


def test_a_ceiling_of_nothing_is_refused(connection: sqlite3.Connection, repo: Repository) -> None:
    fill(repo)
    with pytest.raises(QueryError, match="at least one"):
        count_by(connection, q("label.lane = 2"), "unit", ceiling=0)


def test_a_low_ceiling_truncates_and_says_so(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    fill(repo)
    tally = count_by(connection, q("label.lane = 2"), "unit", ceiling=2)
    assert tally.truncated is True
    assert tally.scanned == 2


def test_a_count_on_a_closed_archive_is_refused(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    fill(repo)
    connection.close()
    with pytest.raises(QueryError, match="refused the count"):
        count_by(connection, q(""), "unit")


def test_an_in_memory_count_on_a_closed_archive_is_refused(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    fill(repo)
    connection.close()
    with pytest.raises(QueryError, match="refused the count"):
        count_by(connection, q("label.lane = 2"), "unit")


def test_the_groups_carry_the_span_they_cover(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    for index in range(4):
        add(repo, "pump-bridge", hours_ago=index)
    group = count_by(connection, q(""), "unit").groups[0]
    assert group.span_ns == 3 * HOUR_NS
