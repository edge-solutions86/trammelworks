from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from stanchion.archive.repository import Repository
from stanchion.archive.schema import open_archive
from stanchion.errors import QueryError
from stanchion.query.ast import Query
from stanchion.query.cursor import Cursor, cursor_params, cursor_sql, parse_cursor
from stanchion.query.execute import search
from stanchion.query.parser import parse_query
from stanchion.query.plan import plan
from stanchion.records import LogRecord, Stream
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-06-10T12:00:00Z")


@pytest.fixture
def connection(tmp_path: Path) -> sqlite3.Connection:
    return open_archive(tmp_path / "archive" / "records.sqlite3")


@pytest.fixture
def repo(connection: sqlite3.Connection) -> Repository:
    return Repository(connection=connection)


def add(repo: Repository, index: int, at_ns: int, unit: str = "pump-bridge") -> None:
    repo.add(
        LogRecord(
            site="US-TX-0417",
            unit=unit,
            stream=Stream.STDOUT,
            observed_at=NanoTime(at_ns),
            message=f"line {index:03d}",
            labels={"lane": "2"} if index % 2 == 0 else {},
        ),
        NOW,
    )


def fill(repo: Repository, count: int, step: int = 1000) -> None:
    for index in range(count):
        add(repo, index, NOW.epoch_ns + index * step)


def q(text: str) -> Query:
    return parse_query(text, NOW)


def messages(result: object) -> list[str]:
    return [item.message for item in result.records]  # type: ignore[attr-defined]


def pages(connection: sqlite3.Connection, text: str, size: int) -> list[list[str]]:
    """Walk every page, following the cursor until none is offered."""
    out: list[list[str]] = []
    cursor: Cursor | None = None
    for _ in range(50):
        found = search(connection, q(f"{text} limit {size}".strip()), after=cursor)
        if not found.records:
            break
        out.append(messages(found))
        cursor = found.next_cursor
        if cursor is None:
            break
    return out


def test_a_cursor_round_trips() -> None:
    original = Cursor(at_ns=1749556800000000000, row_id=42)
    assert parse_cursor(original.encode()) == original


def test_a_cursor_before_the_epoch_round_trips() -> None:
    original = Cursor(at_ns=-500, row_id=1)
    assert parse_cursor(original.encode()) == original


def test_a_cursor_reads_as_its_encoding() -> None:
    assert str(Cursor(at_ns=7, row_id=3)) == "7.3"


def test_a_cursor_knows_its_instant() -> None:
    assert Cursor(at_ns=1_000_000_000, row_id=1).at.epoch_ns == 1_000_000_000


def test_a_negative_row_is_refused() -> None:
    with pytest.raises(QueryError, match="cannot be negative"):
        Cursor(at_ns=1, row_id=-1)


@pytest.mark.parametrize("raw", ["", "nonsense", "1", "1.", ".1", "a.b", "1.2.3", "1,2"])
def test_a_malformed_cursor_is_refused(raw: str) -> None:
    with pytest.raises(QueryError, match="malformed"):
        parse_cursor(raw)


def test_surrounding_space_is_forgiven() -> None:
    assert parse_cursor("  12.3  ") == Cursor(at_ns=12, row_id=3)


def test_the_predicate_breaks_ties_on_the_row() -> None:
    sql = cursor_sql("at")
    assert "at > ?" in sql
    assert "at = ? AND id > ?" in sql


def test_the_predicate_takes_three_values() -> None:
    assert cursor_params(Cursor(at_ns=9, row_id=4)) == [9, 9, 4]


def test_a_plan_without_a_cursor_is_unchanged() -> None:
    built = plan(q("unit = pump-bridge"))
    assert built.full_where == built.where_sql
    assert built.all_params == built.params


def test_a_plan_with_a_cursor_carries_it() -> None:
    built = plan(q("unit = pump-bridge"), after=Cursor(at_ns=5, row_id=2))
    assert "id > ?" in built.full_where
    assert built.all_params == ["pump-bridge", 5, 5, 2]


def test_the_cursor_is_in_the_statement() -> None:
    built = plan(q(""), after=Cursor(at_ns=5, row_id=2))
    assert "id > ?" in built.sql()


def test_a_first_page_offers_a_cursor(connection: sqlite3.Connection, repo: Repository) -> None:
    fill(repo, 10)
    found = search(connection, q("limit 4"))
    assert found.count == 4
    assert found.more is True


def test_a_second_page_carries_on(connection: sqlite3.Connection, repo: Repository) -> None:
    fill(repo, 10)
    first = search(connection, q("limit 4"))
    second = search(connection, q("limit 4"), after=first.next_cursor)
    assert messages(second) == ["line 004", "line 005", "line 006", "line 007"]


def test_the_last_page_offers_no_cursor(connection: sqlite3.Connection, repo: Repository) -> None:
    fill(repo, 6)
    first = search(connection, q("limit 4"))
    second = search(connection, q("limit 4"), after=first.next_cursor)
    assert second.count == 2
    assert second.more is False


def test_paging_sees_every_record_once(connection: sqlite3.Connection, repo: Repository) -> None:
    fill(repo, 25)
    walked = [line for page in pages(connection, "", 4) for line in page]
    assert walked == [f"line {index:03d}" for index in range(25)]


def test_paging_repeats_nothing(connection: sqlite3.Connection, repo: Repository) -> None:
    fill(repo, 25)
    walked = [line for page in pages(connection, "", 4) for line in page]
    assert len(walked) == len(set(walked))


def test_records_sharing_an_instant_are_not_skipped(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    # A container writing a burst gets one runtime timestamp for several lines.
    for index in range(9):
        add(repo, index, NOW.epoch_ns)
    walked = [line for page in pages(connection, "", 2) for line in page]
    assert walked == [f"line {index:03d}" for index in range(9)]


def test_records_sharing_an_instant_are_not_repeated(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    for index in range(9):
        add(repo, index, NOW.epoch_ns)
    walked = [line for page in pages(connection, "", 2) for line in page]
    assert len(walked) == len(set(walked))


def test_a_record_inserted_behind_the_reader_does_not_shift_the_page(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    # The collector is writing while the tool pages. An offset would slide.
    fill(repo, 10)
    first = search(connection, q("limit 4"))
    add(repo, 900, NOW.epoch_ns - 5000)
    second = search(connection, q("limit 4"), after=first.next_cursor)
    assert messages(second)[0] == "line 004"


def test_paging_works_with_a_filter(connection: sqlite3.Connection, repo: Repository) -> None:
    fill(repo, 20)
    walked = [line for page in pages(connection, 'message ~ "line 01"', 3) for line in page]
    assert walked == [f"line 01{digit}" for digit in range(10)]


def test_paging_works_when_a_residual_drops_rows(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    fill(repo, 20)
    walked = [line for page in pages(connection, "label.lane = 2", 3) for line in page]
    assert walked == [f"line {index:03d}" for index in range(0, 20, 2)]


def test_the_cursor_points_at_a_record_that_was_returned(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    # Not at the last row read. The residual dropped rows nobody saw, and
    # resuming past them would skip whatever came next.
    fill(repo, 20)
    found = search(connection, q("label.lane = 2 limit 3"))
    assert found.next_cursor is not None
    assert found.next_cursor.at_ns == found.records[-1].best_at.epoch_ns


def test_a_cursor_past_the_end_returns_nothing(
    connection: sqlite3.Connection, repo: Repository
) -> None:
    fill(repo, 5)
    far = Cursor(at_ns=NOW.epoch_ns + 10_000_000, row_id=0)
    assert search(connection, q(""), after=far).records == []


def test_a_search_with_no_records_offers_no_cursor(connection: sqlite3.Connection) -> None:
    assert search(connection, q("")).more is False
