"""Turning a filter into SQL the archive can index, and saying what is left.

Not every filter can be pushed into SQL. Labels are stored as a JSON blob, and
rather than depend on the JSON functions being compiled into whatever SQLite the
site image happens to carry, a label predicate is left for the evaluator to
apply to the rows that come back.

The interesting part is *how* it is left, because it is not the same answer
everywhere in the tree:

* under an **and**, the two sides are independent. The non-label side is pushed
  into SQL and narrows what comes back; the label side becomes the residual.
* under an **or**, they are not. A row failing the pushed half might still match
  through the half that was not pushed, so pushing either one would drop rows
  that match. The whole ``or`` becomes residual and SQL sees no constraint.
* under a **not**, likewise. Negating a partial constraint is not the negation
  of the whole one.

So one rule about labels decides what a different rule, the SQL where clause, is
allowed to say. Getting that wrong does not produce an error; it produces a
short answer, which is the worst kind of wrong for a dispute review.

Every plan carries its residual, and the executor is required to apply it. A
plan that quietly dropped it would be indistinguishable from a correct one until
somebody counted.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from stanchion.errors import QueryError
from stanchion.query.ast import LABEL_PREFIX as LABEL
from stanchion.query.ast import And, Compare, InSet, Node, Not, Op, Or, Query, Since, Until
from stanchion.query.cursor import Cursor, cursor_params, cursor_sql

#: Filter field to archive column. ``message`` is deliberately not indexed:
#: a substring search cannot use an index anyway and pretending otherwise would
#: only encourage somebody to add one.
COLUMNS: dict[str, str] = {
    "site": "site",
    "unit": "unit",
    "class": "klass",
    "stream": "stream",
    "message": "message",
    "confidence": "confidence",
}

TIME_COLUMN = "COALESCE(corrected_ns, observed_ns)"

SQL_OPERATORS: dict[Op, str] = {
    Op.EQ: "=",
    Op.NE: "!=",
    Op.LT: "<",
    Op.LE: "<=",
    Op.GT: ">",
    Op.GE: ">=",
}


def is_pushable(node: Node) -> bool:
    """True when this subtree can be expressed entirely in SQL."""
    if isinstance(node, Compare | InSet):
        return not node.field.startswith(LABEL)
    if isinstance(node, And | Or):
        return is_pushable(node.left) and is_pushable(node.right)
    if isinstance(node, Not):
        return is_pushable(node.inner)
    return True


@dataclass(slots=True)
class Plan:
    """What to ask SQL for, and what still has to be checked afterwards."""

    where_sql: str = "1"
    params: list[Any] = field(default_factory=list)
    limit: int | None = None
    residual: Node | None = None
    after: Cursor | None = None

    @property
    def needs_post_filter(self) -> bool:
        return self.residual is not None

    @property
    def fully_pushed(self) -> bool:
        return self.residual is None

    @property
    def full_where(self) -> str:
        """The filter and the cursor together.

        The cursor is not part of the filter the user typed and it is not
        optional either: it is what makes the next page the next page. Joining
        them here means no caller can build the statement and forget it.
        """
        if self.after is None:
            return self.where_sql
        return f"({self.where_sql}) AND {cursor_sql(TIME_COLUMN)}"

    @property
    def all_params(self) -> list[Any]:
        if self.after is None:
            return list(self.params)
        return [*self.params, *cursor_params(self.after)]

    def sql(self, columns: str = "*") -> str:
        """The whole statement, ordered the way the archive is read."""
        order = f" ORDER BY {TIME_COLUMN} ASC, id ASC"
        # The LIMIT is only applied in SQL when nothing is left to check.
        # Otherwise the post filter would be handed a short list and would
        # return fewer rows than the caller asked for.
        tail = f" LIMIT {self.limit}" if self.limit is not None and self.fully_pushed else ""
        return f"SELECT {columns} FROM records WHERE {self.full_where}{order}{tail}"


def _compare_sql(node: Compare) -> tuple[str, list[Any]]:
    column = COLUMNS[node.field]
    if node.op is Op.MATCH:
        return f"{column} LIKE ? ESCAPE '\\'", [f"%{_escape_like(node.value)}%"]
    if node.op is Op.NOT_MATCH:
        return f"{column} NOT LIKE ? ESCAPE '\\'", [f"%{_escape_like(node.value)}%"]
    operator = SQL_OPERATORS.get(node.op)
    if operator is None:
        raise QueryError("operator cannot be planned", detail=str(node.op))
    return f"{column} {operator} ?", [node.value]


def _escape_like(value: str) -> str:
    """Escape the wildcards LIKE would otherwise read as pattern syntax."""
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _node_sql(node: Node) -> tuple[str, list[Any]]:
    if isinstance(node, Compare):
        return _compare_sql(node)
    if isinstance(node, InSet):
        holes = ", ".join("?" for _ in node.values)
        keyword = "NOT IN" if node.negated else "IN"
        return f"{COLUMNS[node.field]} {keyword} ({holes})", list(node.values)
    if isinstance(node, Since):
        return f"{TIME_COLUMN} >= ?", [node.at_ns]
    if isinstance(node, Until):
        return f"{TIME_COLUMN} <= ?", [node.at_ns]
    if isinstance(node, And):
        left, left_params = _node_sql(node.left)
        right, right_params = _node_sql(node.right)
        return f"({left} AND {right})", left_params + right_params
    if isinstance(node, Or):
        left, left_params = _node_sql(node.left)
        right, right_params = _node_sql(node.right)
        return f"({left} OR {right})", left_params + right_params
    if isinstance(node, Not):
        inner, params = _node_sql(node.inner)
        return f"(NOT {inner})", params
    raise QueryError("node cannot be planned", detail=type(node).__name__)


def _split(node: Node) -> tuple[Node | None, Node | None]:
    """Divide a tree into the part SQL can take and the part it cannot."""
    if is_pushable(node):
        return node, None
    if isinstance(node, And):
        left_push, left_keep = _split(node.left)
        right_push, right_keep = _split(node.right)
        pushed = _join(left_push, right_push)
        kept = _join(left_keep, right_keep)
        return pushed, kept
    # An or or a not that is not wholly pushable cannot be split at all.
    return None, node


def _join(left: Node | None, right: Node | None) -> Node | None:
    if left is None:
        return right
    if right is None:
        return left
    return And(left=left, right=right)


def plan(query: Query, after: Cursor | None = None) -> Plan:
    """Work out the statement and what the evaluator still has to do."""
    if query.where is None:
        return Plan(limit=query.limit, after=after)
    pushed, residual = _split(query.where)
    if pushed is None:
        return Plan(limit=query.limit, residual=residual, after=after)
    where_sql, params = _node_sql(pushed)
    return Plan(
        where_sql=where_sql,
        params=params,
        limit=query.limit,
        residual=residual,
        after=after,
    )
