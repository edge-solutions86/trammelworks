from __future__ import annotations

import pytest

from stanchion.errors import QueryError
from stanchion.query.ast import (
    And,
    Compare,
    InSet,
    Not,
    Op,
    Or,
    Since,
    Until,
    check_field,
    fields_used,
)
from stanchion.query.parser import parse_instant, parse_query
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-03-04T12:00:00Z")


def parse(text: str):  # type: ignore[no-untyped-def]
    return parse_query(text, NOW)


def test_an_empty_filter_matches_everything() -> None:
    found = parse("")
    assert found.matches_everything is True
    assert found.render() == "everything"


@pytest.mark.parametrize("field", ["site", "unit", "class", "stream", "message", "confidence"])
def test_every_known_field_is_accepted(field: str) -> None:
    assert check_field(field) == field


def test_a_label_field_is_accepted() -> None:
    assert check_field("label.lane") == "label.lane"


def test_a_label_with_no_name_is_refused() -> None:
    with pytest.raises(QueryError, match="a label needs a name"):
        check_field("label.")


def test_an_unknown_field_is_refused() -> None:
    with pytest.raises(QueryError, match="unknown field"):
        check_field("unti")


def test_a_field_name_is_lowercased() -> None:
    assert check_field("UNIT") == "unit"


def test_a_simple_comparison_parses() -> None:
    found = parse("unit = payment-kernel")
    assert isinstance(found.where, Compare)
    assert found.where.field == "unit"
    assert found.where.op is Op.EQ
    assert found.where.value == "payment-kernel"


@pytest.mark.parametrize("operator", ["=", "!=", "~", "!~", "<", "<=", ">", ">="])
def test_every_operator_parses(operator: str) -> None:
    found = parse(f"unit {operator} x")
    assert isinstance(found.where, Compare)
    assert found.where.op is Op(operator)


def test_a_quoted_value_keeps_its_spaces() -> None:
    found = parse('message ~ "auth declined"')
    assert isinstance(found.where, Compare)
    assert found.where.value == "auth declined"


def test_an_and_parses_as_a_tree() -> None:
    found = parse("unit = a and stream = stderr")
    assert isinstance(found.where, And)


def test_an_or_parses_as_a_tree() -> None:
    found = parse("unit = a or unit = b")
    assert isinstance(found.where, Or)


def test_and_binds_tighter_than_or() -> None:
    found = parse("unit = a or unit = b and stream = stderr")
    assert isinstance(found.where, Or)
    assert isinstance(found.where.right, And)


def test_parentheses_override_the_precedence() -> None:
    found = parse("(unit = a or unit = b) and stream = stderr")
    assert isinstance(found.where, And)
    assert isinstance(found.where.left, Or)


def test_an_unclosed_parenthesis_is_refused() -> None:
    with pytest.raises(QueryError, match="closing parenthesis"):
        parse("(unit = a")


def test_not_negates_one_term() -> None:
    found = parse("not unit = a")
    assert isinstance(found.where, Not)
    assert isinstance(found.where.inner, Compare)


def test_not_binds_tighter_than_and() -> None:
    found = parse("not unit = a and stream = stderr")
    assert isinstance(found.where, And)
    assert isinstance(found.where.left, Not)


def test_two_adjacent_terms_are_joined_by_an_implied_and() -> None:
    found = parse("unit = a stream = stderr")
    assert isinstance(found.where, And)


def test_an_in_list_parses() -> None:
    found = parse("unit in (pump-bridge, tank-gauge)")
    assert isinstance(found.where, InSet)
    assert found.where.values == ("pump-bridge", "tank-gauge")


def test_an_in_list_of_one_is_allowed() -> None:
    found = parse("unit in (pump-bridge)")
    assert isinstance(found.where, InSet)
    assert found.where.values == ("pump-bridge",)


def test_an_empty_in_list_is_refused() -> None:
    with pytest.raises(QueryError, match="expected a value"):
        parse("unit in ()")


def test_since_reads_a_duration_back_from_now() -> None:
    found = parse("since 2h")
    assert isinstance(found.where, Since)
    assert found.where.at_ns == NanoTime.parse("2025-03-04T10:00:00Z").epoch_ns


def test_since_reads_an_absolute_timestamp() -> None:
    found = parse("since 2025-03-04T09:30:00Z")
    assert isinstance(found.where, Since)
    assert found.where.at_ns == NanoTime.parse("2025-03-04T09:30:00Z").epoch_ns


def test_until_reads_a_duration() -> None:
    found = parse("until 30m")
    assert isinstance(found.where, Until)
    assert found.where.at_ns == NanoTime.parse("2025-03-04T11:30:00Z").epoch_ns


def test_a_window_pairs_with_a_condition() -> None:
    found = parse("since 2h and unit = payment-kernel")
    assert isinstance(found.where, And)


def test_an_unreadable_time_is_refused() -> None:
    with pytest.raises(QueryError, match="not a time or a duration"):
        parse("since yesterday")


def test_a_limit_is_read() -> None:
    assert parse("unit = a limit 40").limit == 40


def test_a_limit_alone_is_allowed() -> None:
    found = parse("limit 5")
    assert found.limit == 5
    assert found.matches_everything is True


def test_a_limit_of_zero_is_refused() -> None:
    with pytest.raises(QueryError, match="at least one"):
        parse("limit 0")


def test_a_non_numeric_limit_is_refused() -> None:
    with pytest.raises(QueryError, match="whole number"):
        parse("limit lots")


def test_a_missing_operator_is_refused() -> None:
    with pytest.raises(QueryError, match="comparison operator"):
        parse("unit payment-kernel x")


def test_a_missing_value_is_refused() -> None:
    with pytest.raises(QueryError, match="expected a value"):
        parse("unit =")


def test_a_keyword_where_a_field_belongs_is_refused() -> None:
    with pytest.raises(QueryError, match="expected a field name"):
        parse("and unit = a")


def test_trailing_input_is_refused() -> None:
    with pytest.raises(QueryError, match="trailing input"):
        parse("unit = a )")


def test_the_error_names_the_token_and_its_position() -> None:
    with pytest.raises(QueryError) as caught:
        parse("unit = a )")
    assert caught.value.detail is not None
    assert "at 9" in caught.value.detail


def test_a_filter_renders_back() -> None:
    assert parse("unit = payment-kernel").render() == "unit = payment-kernel"


def test_a_value_with_spaces_renders_quoted() -> None:
    assert parse('message ~ "auth declined"').render() == 'message ~ "auth declined"'


def test_a_tree_renders_with_its_parentheses() -> None:
    assert parse("unit = a and stream = stderr").render() == "(unit = a and stream = stderr)"


def test_a_rendered_filter_parses_again() -> None:
    text = "unit in (a, b) and not message ~ heartbeat limit 10"
    once = parse(text)
    assert parse(once.render()).render() == once.render()


def test_the_fields_used_are_reported() -> None:
    assert fields_used(parse("unit = a and stream = stderr").where) == frozenset({"unit", "stream"})


def test_a_window_uses_no_fields() -> None:
    assert fields_used(parse("since 2h").where) == frozenset()


def test_an_empty_filter_uses_no_fields() -> None:
    assert fields_used(None) == frozenset()


def test_parse_instant_reads_both_forms() -> None:
    assert parse_instant("2h", NOW) == NanoTime.parse("2025-03-04T10:00:00Z").epoch_ns
    assert (
        parse_instant("2025-01-01T00:00:00Z", NOW)
        == NanoTime.parse("2025-01-01T00:00:00Z").epoch_ns
    )


def test_not_in_parses_as_a_negated_set() -> None:
    node = parse_query("unit not in (a, b)", NOW).where
    assert node == InSet(field="unit", values=("a", "b"), negated=True)


def test_not_in_renders_the_way_it_was_typed() -> None:
    node = parse_query("unit not in (a, b)", NOW).where
    assert node is not None
    assert node.render() == "unit not in (a, b)"


def test_a_plain_in_is_not_negated() -> None:
    node = parse_query("unit in (a)", NOW).where
    assert isinstance(node, InSet)
    assert node.negated is False


def test_not_without_in_after_a_field_is_refused() -> None:
    with pytest.raises(QueryError, match="expected in after not"):
        parse_query("unit not = a", NOW)


def test_a_leading_not_still_negates_a_whole_term() -> None:
    node = parse_query("not unit in (a)", NOW).where
    assert isinstance(node, Not)
