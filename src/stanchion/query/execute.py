"""Running a filter against the archive.

Three steps, and the middle one is the one that has to be right: plan, fetch,
then apply whatever the plan could not push into SQL. The residual is not
optional. A caller that skipped it would get more rows than the filter asked
for, and every one of them would look like a match.

The fetch is bounded even when the filter is not. A support engineer who types
``message ~ e`` against a site holding four million rows should get an answer
and a warning, not a laptop that stops responding, so the read stops at a
ceiling and says it was truncated. Being told the answer is incomplete is worth
a great deal more than a complete answer nobody waited for.
"""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field

from stanchion.archive.repository import StoredRecord, row_to_record
from stanchion.errors import QueryError
from stanchion.query.ast import Query
from stanchion.query.evaluate import matches
from stanchion.query.plan import Plan, plan

#: The most rows a single search will pull out of SQLite before giving up.
DEFAULT_SCAN_CEILING = 200_000


@dataclass(slots=True)
class SearchResult:
    """What a search found, and what it had to do to find it."""

    records: list[StoredRecord] = field(default_factory=list)
    scanned: int = 0
    post_filtered: int = 0
    truncated: bool = False
    plan_sql: str = ""

    @property
    def count(self) -> int:
        return len(self.records)

    def describe(self) -> str:
        end = ", truncated" if self.truncated else ""
        return f"{self.count} records from {self.scanned} scanned{end}"


def search(
    connection: sqlite3.Connection,
    query: Query,
    ceiling: int = DEFAULT_SCAN_CEILING,
) -> SearchResult:
    """Plan, fetch and finish a filter."""
    if ceiling < 1:
        raise QueryError("scan ceiling must be at least one", detail=str(ceiling))
    built = plan(query)
    result = SearchResult(plan_sql=built.sql())
    wanted = query.limit
    for row in _fetch(connection, built, ceiling):
        result.scanned += 1
        record = row_to_record(row)
        if built.residual is not None and not matches(built.residual, record):
            result.post_filtered += 1
            continue
        result.records.append(record)
        if wanted is not None and len(result.records) >= wanted:
            break
    else:
        result.truncated = result.scanned >= ceiling
    return result


def _fetch(connection: sqlite3.Connection, built: Plan, ceiling: int) -> list[sqlite3.Row]:
    statement = built.sql()
    if built.limit is None or not built.fully_pushed:
        statement = f"{statement} LIMIT {ceiling}"
    try:
        return list(connection.execute(statement, built.params))
    except sqlite3.DatabaseError as exc:
        raise QueryError("the archive refused the query", detail=str(exc)) from exc


def count_matching(
    connection: sqlite3.Connection, query: Query, ceiling: int = DEFAULT_SCAN_CEILING
) -> int:
    """How many records match, ignoring any limit on the query."""
    unlimited = Query(where=query.where, limit=None)
    return search(connection, unlimited, ceiling).count


def explain(query: Query) -> str:
    """A line describing how a filter will be run, for the doctor command."""
    built = plan(query)
    if built.fully_pushed:
        return f"sql: {built.where_sql}"
    residual = built.residual.render() if built.residual is not None else ""
    return f"sql: {built.where_sql}; then in memory: {residual}"
