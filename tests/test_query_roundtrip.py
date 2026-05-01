"""Every filter has to read back as the filter that ran.

The reports quote the filter they were produced with, and a dispute review that
turns on which records a report covered is not the moment to discover that the
quoted text parses to a different tree. So the property is not "the parser
works", it is that ``parse(render(tree))`` is ``tree`` for every tree the
language can express, including the ones nobody would type.
"""

from __future__ import annotations

from hypothesis import given, settings
from hypothesis import strategies as st

from stanchion.query.ast import (
    And,
    Compare,
    InSet,
    Node,
    Not,
    Op,
    Or,
    Query,
    Since,
    Until,
    fields_used,
    walk,
)
from stanchion.query.evaluate import matches
from stanchion.query.parser import parse_query
from stanchion.query.plan import is_pushable, plan
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-06-10T12:00:00Z")

PLAIN_FIELDS = ["site", "unit", "class", "stream", "message", "confidence"]
LABEL_FIELDS = ["label.lane", "label.pump", "label.truncated"]

values = st.text(
    alphabet=st.characters(min_codepoint=32, max_codepoint=126, blacklist_characters="\\"),
    min_size=1,
    max_size=12,
)
fields = st.sampled_from([*PLAIN_FIELDS, *LABEL_FIELDS])
text_ops = st.sampled_from([Op.EQ, Op.NE, Op.MATCH, Op.NOT_MATCH, Op.LT, Op.GT])


def comparisons() -> st.SearchStrategy[Node]:
    return st.builds(Compare, field=fields, op=text_ops, value=values)


def in_sets() -> st.SearchStrategy[Node]:
    return st.builds(
        InSet,
        field=fields,
        values=st.lists(values, min_size=1, max_size=4).map(tuple),
        negated=st.booleans(),
    )


def instants() -> st.SearchStrategy[Node]:
    stamps = st.integers(min_value=0, max_value=2_000_000_000).map(
        lambda seconds: NanoTime(seconds * 1_000_000_000).isoformat()
    )
    return st.one_of(
        stamps.map(lambda raw: Since(at_ns=NanoTime.parse(raw).epoch_ns, written=raw)),
        stamps.map(lambda raw: Until(at_ns=NanoTime.parse(raw).epoch_ns, written=raw)),
    )


def trees() -> st.SearchStrategy[Node]:
    leaves = st.one_of(comparisons(), in_sets(), instants())
    return st.recursive(
        leaves,
        lambda inner: st.one_of(
            st.builds(And, left=inner, right=inner),
            st.builds(Or, left=inner, right=inner),
            st.builds(Not, inner=inner),
        ),
        max_leaves=6,
    )


def parse(text: str) -> Query:
    return parse_query(text, NOW)


@given(trees())
@settings(max_examples=200, deadline=None)
def test_a_tree_survives_being_written_out_and_read_back(node: Node) -> None:
    assert parse(node.render()).where == node


@given(trees())
@settings(max_examples=200, deadline=None)
def test_rendering_is_stable_across_a_round_trip(node: Node) -> None:
    once = node.render()
    twice = parse(once).where
    assert twice is not None
    assert twice.render() == once


@given(trees(), st.integers(min_value=1, max_value=5000))
@settings(max_examples=100, deadline=None)
def test_a_limit_survives_too(node: Node, limit: int) -> None:
    original = Query(where=node, limit=limit)
    assert parse(original.render()) == original


@given(trees())
@settings(max_examples=150, deadline=None)
def test_every_tree_can_be_planned(node: Node) -> None:
    built = plan(Query(where=node))
    assert built.sql().startswith("SELECT")


@given(trees())
@settings(max_examples=150, deadline=None)
def test_a_plan_binds_one_value_per_hole(node: Node) -> None:
    built = plan(Query(where=node))
    assert built.where_sql.count("?") == len(built.params)


@given(trees())
@settings(max_examples=150, deadline=None)
def test_a_tree_that_touches_a_label_is_never_fully_pushed(node: Node) -> None:
    built = plan(Query(where=node))
    touches_label = any(name.startswith("label.") for name in fields_used(node))
    if touches_label:
        assert not built.fully_pushed
    else:
        assert built.fully_pushed


@given(trees())
@settings(max_examples=150, deadline=None)
def test_a_residual_only_appears_where_something_could_not_be_pushed(node: Node) -> None:
    built = plan(Query(where=node))
    assert built.fully_pushed == is_pushable(node)


@given(trees())
@settings(max_examples=100, deadline=None)
def test_walking_a_tree_reaches_every_node(node: Node) -> None:
    found = walk(node)
    assert node in found
    assert all(isinstance(item, Node) for item in found)


@given(trees())
@settings(max_examples=100, deadline=None)
def test_the_evaluator_answers_without_raising(node: Node) -> None:
    class Row:
        site = "US-TX-0417"
        unit = "pump-bridge"
        klass = "standard"
        stream = "stdout"
        message = "nozzle 1 lifted"
        confidence = "measured"
        labels = {"lane": "2"}
        observed_at = NOW
        corrected_at = None

        @property
        def best_at(self) -> NanoTime:
            return NOW

    assert matches(node, Row()) in (True, False)


@given(values)
@settings(max_examples=200, deadline=None)
def test_any_value_survives_quoting(value: str) -> None:
    node = Compare(field="message", op=Op.EQ, value=value)
    assert parse(node.render()).where == node
