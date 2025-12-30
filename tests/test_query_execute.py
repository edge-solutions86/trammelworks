from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from stanchion.archive.repository import Repository
from stanchion.archive.schema import open_archive
from stanchion.errors import QueryError
from stanchion.query.evaluate import run
from stanchion.query.execute import count_matching, explain, search
from stanchion.query.parser import parse_query
from stanchion.records import LogRecord, Stream
from stanchion.spool.priority import PriorityTable
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-03-04T12:00:00Z")
TABLE = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})

CORPUS = [
    ("payment-kernel", "11:00:00", "auth approved for lane 3", {"lane": "3"}, Stream.STDOUT),
    ("payment-kernel", "11:15:00", "auth declined for lane 4", {"lane": "4"}, Stream.STDERR),
    ("pump-bridge", "11:30:00", "nozzle 1 lifted", {"lane": "1"}, Stream.STDOUT),
    ("pump-bridge", "11:45:00", "nozzle 1 replaced", {}, Stream.STDOUT),
    ("price-sign", "11:50:00", "heartbeat", {}, Stream.STDOUT),
    ("tank-gauge", "11:55:00", "level 41200 ml", {"tank": "2"}, Stream.STDOUT),
]


@pytest.fixture
def connection(tmp_path: Path) -> sqlite3.Connection:
    return open_archive(tmp_path / "archive" / "records.sqlite3")


@pytest.fixture
def repo(connection: sqlite3.Connection) -> Repository:
    built = Repository(connection=connection, table=TABLE)
    for unit, when, message, labels, stream in CORPUS:
        built.add(
            LogRecord(
                site="US-TX-0417",
                unit=unit,
                stream=stream,
                observed_at=NanoTime.parse(f"2025-03-04T{when}Z"),
                message=message,
                labels=labels,
            ),
            NOW,
        )
    return built


def messages(connection: sqlite3.Connection, text: str) -> list[str]:
    found = search(connection, parse_query(text, NOW))
    return [record.message for record in found.records]


def test_an_empty_filter_returns_everything(connection, repo) -> None:  # type: ignore[no-untyped-def]
    assert len(messages(connection, "")) == len(CORPUS)


def test_the_records_come_back_oldest_first(connection, repo) -> None:  # type: ignore[no-untyped-def]
    assert messages(connection, "")[0] == "auth approved for lane 3"


def test_a_unit_filter_narrows(connection, repo) -> None:  # type: ignore[no-untyped-def]
    assert len(messages(connection, "unit = pump-bridge")) == 2


def test_a_class_filter_narrows(connection, repo) -> None:  # type: ignore[no-untyped-def]
    assert len(messages(connection, "class = payment")) == 2


def test_a_contains_filter_narrows(connection, repo) -> None:  # type: ignore[no-untyped-def]
    assert messages(connection, "message ~ declined") == ["auth declined for lane 4"]


def test_a_contains_filter_ignores_case(connection, repo) -> None:  # type: ignore[no-untyped-def]
    assert len(messages(connection, "message ~ NOZZLE")) == 2


def test_an_in_list_narrows(connection, repo) -> None:  # type: ignore[no-untyped-def]
    assert len(messages(connection, "unit in (pump-bridge, tank-gauge)")) == 3


def test_a_window_narrows(connection, repo) -> None:  # type: ignore[no-untyped-def]
    assert len(messages(connection, "since 2025-03-04T11:40:00Z")) == 3


def test_two_windows_bracket_the_answer(connection, repo) -> None:  # type: ignore[no-untyped-def]
    found = messages(connection, "since 2025-03-04T11:20:00Z and until 2025-03-04T11:47:00Z")
    assert found == ["nozzle 1 lifted", "nozzle 1 replaced"]


def test_a_label_filter_is_applied_after_the_fetch(connection, repo) -> None:  # type: ignore[no-untyped-def]
    found = search(connection, parse_query("label.lane = 3", NOW))
    assert [record.message for record in found.records] == ["auth approved for lane 3"]
    assert found.post_filtered > 0


def test_a_label_under_an_and_still_narrows_in_sql(connection, repo) -> None:  # type: ignore[no-untyped-def]
    found = search(connection, parse_query("unit = payment-kernel and label.lane = 4", NOW))
    assert [record.message for record in found.records] == ["auth declined for lane 4"]
    assert found.scanned == 2


def test_a_label_under_an_or_still_gives_the_right_answer(connection, repo) -> None:  # type: ignore[no-untyped-def]
    found = messages(connection, "unit = price-sign or label.tank = 2")
    assert sorted(found) == ["heartbeat", "level 41200 ml"]


def test_a_limit_bounds_the_answer(connection, repo) -> None:  # type: ignore[no-untyped-def]
    assert len(messages(connection, "limit 2")) == 2


def test_a_limit_is_honoured_after_a_post_filter(connection, repo) -> None:  # type: ignore[no-untyped-def]
    found = search(connection, parse_query("not label.lane = 1 limit 2", NOW))
    assert len(found.records) == 2


def test_a_filter_matching_nothing_returns_nothing(connection, repo) -> None:  # type: ignore[no-untyped-def]
    assert messages(connection, "unit = absent-unit") == []


def test_the_scan_ceiling_truncates_and_says_so(connection, repo) -> None:  # type: ignore[no-untyped-def]
    found = search(connection, parse_query("", NOW), ceiling=2)
    assert found.truncated is True
    assert found.count == 2


def test_a_ceiling_below_one_is_refused(connection, repo) -> None:  # type: ignore[no-untyped-def]
    with pytest.raises(QueryError, match="at least one"):
        search(connection, parse_query("", NOW), ceiling=0)


def test_the_result_describes_itself(connection, repo) -> None:  # type: ignore[no-untyped-def]
    found = search(connection, parse_query("unit = pump-bridge", NOW))
    assert found.describe() == "2 records from 2 scanned"


def test_counting_ignores_the_limit(connection, repo) -> None:  # type: ignore[no-untyped-def]
    assert count_matching(connection, parse_query("limit 1", NOW)) == len(CORPUS)


def test_explain_shows_a_pushed_filter() -> None:
    assert explain(parse_query("unit = a", NOW)) == "sql: unit = ?"


def test_explain_names_what_is_left_in_memory() -> None:
    line = explain(parse_query("unit = a and label.lane = 3", NOW))
    assert "then in memory: label.lane = 3" in line


@pytest.mark.parametrize(
    "text",
    [
        "",
        "unit = pump-bridge",
        "class = payment",
        "message ~ nozzle",
        "message !~ nozzle",
        "unit in (pump-bridge, tank-gauge)",
        "stream = stderr",
        "since 2025-03-04T11:40:00Z",
        "until 2025-03-04T11:20:00Z",
        "unit = pump-bridge and message ~ lifted",
        "unit = pump-bridge or class = payment",
        "not unit = pump-bridge",
        "label.lane = 3",
        "label.lane = 3 or unit = price-sign",
        "unit = payment-kernel and not label.lane = 3",
        "(unit = pump-bridge or unit = tank-gauge) and not message ~ level",
    ],
)
def test_the_planner_and_the_evaluator_agree(connection, repo, text: str) -> None:  # type: ignore[no-untyped-def]
    """The differential test: SQL must say exactly what the tree says."""
    query = parse_query(text, NOW)
    through_sql = [record.message for record in search(connection, query).records]
    everything = repo.between(
        NanoTime.parse("2025-03-04T00:00:00Z"), NanoTime.parse("2025-03-05T00:00:00Z")
    )
    in_memory = [record.message for record in run(query, list(everything))]
    assert through_sql == in_memory
