"""The shape a parsed filter has.

Kept apart from the parser so that the two consumers can be written against the
tree rather than against each other. There are two of them and they are
genuinely different: the evaluator walks a record in memory, and the planner
turns the same tree into SQL the archive can index. A tree that suited only one
would push the other into re-parsing.

Every node renders back to something the parser accepts. That is not for
prettiness: the reports quote the filter they ran, and a filter that reads back
differently from what was typed is how somebody ends up arguing about which
records a report covered.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from stanchion.errors import QueryError

#: The fields a filter may name. Anything else is a typo, and a typo that
#: silently matched nothing would be read as "there were no such records".
FIELDS = frozenset({"site", "unit", "class", "stream", "message", "confidence"})

LABEL_PREFIX = "label."


class Op(StrEnum):
    """What a comparison does."""

    EQ = "="
    NE = "!="
    MATCH = "~"
    NOT_MATCH = "!~"
    LT = "<"
    LE = "<="
    GT = ">"
    GE = ">="


TEXT_ONLY = frozenset({Op.MATCH, Op.NOT_MATCH})


def check_field(name: str) -> str:
    """Validate a field name, allowing any ``label.x``."""
    cleaned = name.strip().lower()
    if cleaned.startswith(LABEL_PREFIX):
        if len(cleaned) == len(LABEL_PREFIX):
            raise QueryError("a label needs a name", detail=name)
        return cleaned
    if cleaned not in FIELDS:
        raise QueryError("unknown field", detail=name)
    return cleaned


class Node:
    """Base of every node, so a walk can be written once."""

    def render(self) -> str:
        raise NotImplementedError


@dataclass(frozen=True, slots=True)
class Compare(Node):
    """One field against one value."""

    field: str
    op: Op
    value: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "field", check_field(self.field))

    def render(self) -> str:
        return f"{self.field} {self.op.value} {_quote(self.value)}"


@dataclass(frozen=True, slots=True)
class InSet(Node):
    """One field against a set of values."""

    field: str
    values: tuple[str, ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "field", check_field(self.field))
        if not self.values:
            raise QueryError("an in list cannot be empty", detail=self.field)

    def render(self) -> str:
        inner = ", ".join(_quote(value) for value in self.values)
        return f"{self.field} in ({inner})"


@dataclass(frozen=True, slots=True)
class Since(Node):
    """Everything at or after an instant."""

    at_ns: int
    written: str = ""

    def render(self) -> str:
        return f"since {self.written or self.at_ns}"


@dataclass(frozen=True, slots=True)
class Until(Node):
    """Everything at or before an instant."""

    at_ns: int
    written: str = ""

    def render(self) -> str:
        return f"until {self.written or self.at_ns}"


@dataclass(frozen=True, slots=True)
class And(Node):
    left: Node
    right: Node

    def render(self) -> str:
        return f"({self.left.render()} and {self.right.render()})"


@dataclass(frozen=True, slots=True)
class Or(Node):
    left: Node
    right: Node

    def render(self) -> str:
        return f"({self.left.render()} or {self.right.render()})"


@dataclass(frozen=True, slots=True)
class Not(Node):
    inner: Node

    def render(self) -> str:
        return f"not {self.inner.render()}"


@dataclass(frozen=True, slots=True)
class Query:
    """A whole filter: what to match and how much to return."""

    where: Node | None = None
    limit: int | None = None

    def render(self) -> str:
        parts = []
        if self.where is not None:
            parts.append(self.where.render())
        if self.limit is not None:
            parts.append(f"limit {self.limit}")
        return " ".join(parts) if parts else "everything"

    @property
    def matches_everything(self) -> bool:
        return self.where is None


def _quote(value: str) -> str:
    """Quote a value only where the parser would need it back."""
    if value and all(character.isalnum() or character in "-_.:" for character in value):
        return value
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def walk(node: Node) -> list[Node]:
    """Every node in the tree, parents before children."""
    out: list[Node] = [node]
    if isinstance(node, And | Or):
        out.extend(walk(node.left))
        out.extend(walk(node.right))
    elif isinstance(node, Not):
        out.extend(walk(node.inner))
    return out


def fields_used(node: Node | None) -> frozenset[str]:
    """Which fields a tree touches, for the planner and the doctor."""
    if node is None:
        return frozenset()
    found = set()
    for item in walk(node):
        if isinstance(item, Compare | InSet):
            found.add(item.field)
    return frozenset(found)
