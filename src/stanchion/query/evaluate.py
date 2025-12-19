"""Deciding whether one record matches a filter.

There are two things that run a filter and this is the simpler of them. The
planner turns the tree into SQL so the archive can use its indexes; this walks
the tree against a record already in hand. Both are needed, and neither is a
fallback for the other:

* the archive is queried through the planner, because a site holds four million
  rows and walking them in Python is a minute
* the live tail is filtered through here, because those records have not been
  stored yet and there is nothing to query

Keeping both also gives the planner an oracle. A property test can generate a
filter, run it both ways over the same records and demand the same answer, which
is the only way to be confident that a hand written SQL translation says what
the tree says.

Comparisons are string comparisons except on the two time fields, and ``~`` is
"contains, ignoring case" rather than a regular expression. A support engineer
typing a filter into a terminal wants substring search; the one who genuinely
wants a regular expression can pipe the output through grep.
"""

from __future__ import annotations

from typing import Any, Protocol

from stanchion.errors import QueryError
from stanchion.query.ast import LABEL_PREFIX as LABEL
from stanchion.query.ast import And, Compare, InSet, Node, Not, Op, Or, Query, Since, Until


class Matchable(Protocol):
    """What a record has to expose to be filtered."""

    site: str
    unit: str
    klass: str
    message: str
    confidence: str
    labels: dict[str, str]


def field_value(record: Any, field: str) -> str | None:
    """Read one field off a record, or None where it has no such thing."""
    if field.startswith(LABEL):
        found = record.labels.get(field[len(LABEL) :])
        return None if found is None else str(found)
    if field == "class":
        return str(getattr(record, "klass", ""))
    if field == "stream":
        return str(getattr(record, "stream", ""))
    value = getattr(record, field, None)
    return None if value is None else str(value)


def compare_values(left: str, op: Op, right: str) -> bool:
    """One comparison, with the case rules the language promises."""
    if op is Op.EQ:
        return left == right
    if op is Op.NE:
        return left != right
    if op is Op.MATCH:
        return right.lower() in left.lower()
    if op is Op.NOT_MATCH:
        return right.lower() not in left.lower()
    if op is Op.LT:
        return left < right
    if op is Op.LE:
        return left <= right
    if op is Op.GT:
        return left > right
    if op is Op.GE:
        return left >= right
    raise QueryError("unknown operator", detail=str(op))


def record_instant(record: Any) -> int:
    """The time a window is measured against: corrected where there is one."""
    corrected = getattr(record, "corrected_at", None)
    if corrected is not None:
        return int(corrected.epoch_ns)
    return int(record.observed_at.epoch_ns)


def matches(node: Node | None, record: Any) -> bool:
    """Walk the tree against one record."""
    if node is None:
        return True
    if isinstance(node, And):
        return matches(node.left, record) and matches(node.right, record)
    if isinstance(node, Or):
        return matches(node.left, record) or matches(node.right, record)
    if isinstance(node, Not):
        return not matches(node.inner, record)
    if isinstance(node, Since):
        return record_instant(record) >= node.at_ns
    if isinstance(node, Until):
        return record_instant(record) <= node.at_ns
    if isinstance(node, Compare):
        found = field_value(record, node.field)
        if found is None:
            # An absent label matches only a negated test. Treating it as an
            # empty string would make "label.lane != 3" true for every record
            # that has no lane at all, which reads as a claim about lanes.
            return node.op in (Op.NE, Op.NOT_MATCH)
        return compare_values(found, node.op, node.value)
    if isinstance(node, InSet):
        found = field_value(record, node.field)
        return found is not None and found in node.values
    raise QueryError("unknown filter node", detail=type(node).__name__)


def run(query: Query, records: list[Any]) -> list[Any]:
    """Filter a list, honouring the limit."""
    kept = [record for record in records if matches(query.where, record)]
    if query.limit is not None:
        return kept[: query.limit]
    return kept


def count(query: Query, records: list[Any]) -> int:
    """How many match, ignoring the limit."""
    return sum(1 for record in records if matches(query.where, record))
