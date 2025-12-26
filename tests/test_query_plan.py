from __future__ import annotations

import pytest

from stanchion.errors import QueryError
from stanchion.query.ast import Compare, Op, Query
from stanchion.query.parser import parse_query
from stanchion.query.plan import is_pushable, plan
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-03-04T12:00:00Z")


def planned(text: str):  # type: ignore[no-untyped-def]
    return plan(parse_query(text, NOW))


def test_an_empty_filter_plans_to_everything() -> None:
    found = planned("")
    assert found.where_sql == "1"
    assert found.params == []
    assert found.fully_pushed is True


def test_an_equality_becomes_a_bound_parameter() -> None:
    found = planned("unit = payment-kernel")
    assert found.where_sql == "unit = ?"
    assert found.params == ["payment-kernel"]


def test_the_class_field_maps_to_its_column() -> None:
    assert planned("class = payment").where_sql == "klass = ?"


def test_a_value_never_reaches_the_sql_text() -> None:
    found = planned('unit = "x; DROP TABLE records; --"')
    assert "DROP" not in found.where_sql
    assert found.where_sql == "unit = ?"
    assert found.params == ["x; DROP TABLE records; --"]


def test_a_contains_becomes_a_like() -> None:
    found = planned("message ~ declined")
    assert "LIKE ?" in found.where_sql
    assert found.params == ["%declined%"]


def test_a_negated_contains_becomes_a_not_like() -> None:
    assert "NOT LIKE ?" in planned("message !~ heartbeat").where_sql


def test_like_wildcards_in_the_value_are_escaped() -> None:
    found = planned("message ~ '100%_done'")
    assert found.params == ["%100\\%\\_done%"]
    assert "ESCAPE" in found.where_sql


def test_an_in_list_becomes_placeholders() -> None:
    found = planned("unit in (a, b, c)")
    assert found.where_sql == "unit IN (?, ?, ?)"
    assert found.params == ["a", "b", "c"]


def test_a_since_window_compares_the_corrected_time() -> None:
    found = planned("since 2h")
    assert "COALESCE(corrected_ns, observed_ns) >= ?" in found.where_sql
    assert found.params == [NanoTime.parse("2025-03-04T10:00:00Z").epoch_ns]


def test_an_until_window_compares_the_other_way() -> None:
    assert "<= ?" in planned("until 2h").where_sql


def test_an_and_joins_with_and() -> None:
    found = planned("unit = a and stream = stderr")
    assert found.where_sql == "(unit = ? AND stream = ?)"
    assert found.params == ["a", "stderr"]


def test_an_or_joins_with_or() -> None:
    assert planned("unit = a or unit = b").where_sql == "(unit = ? OR unit = ?)"


def test_a_not_becomes_a_sql_not() -> None:
    assert planned("not unit = a").where_sql == "(NOT unit = ?)"


def test_the_parameters_stay_in_tree_order() -> None:
    found = planned("unit = a and (stream = b or class = c)")
    assert found.params == ["a", "b", "c"]


def test_a_plain_filter_is_fully_pushed() -> None:
    assert planned("unit = a and since 2h").fully_pushed is True


def test_a_label_predicate_is_not_pushable() -> None:
    assert is_pushable(Compare(field="label.lane", op=Op.EQ, value="3")) is False


def test_a_label_alone_leaves_everything_to_the_evaluator() -> None:
    found = planned("label.lane = 3")
    assert found.where_sql == "1"
    assert found.needs_post_filter is True


def test_a_label_under_an_and_pushes_the_other_side() -> None:
    found = planned("unit = payment-kernel and label.lane = 3")
    assert found.where_sql == "unit = ?"
    assert found.params == ["payment-kernel"]
    assert found.needs_post_filter is True


def test_a_label_under_an_or_pushes_nothing() -> None:
    # Pushing the non label half of an or would drop rows that match only
    # through the half that was not pushed.
    found = planned("unit = payment-kernel or label.lane = 3")
    assert found.where_sql == "1"
    assert found.needs_post_filter is True


def test_a_label_under_a_not_pushes_nothing() -> None:
    assert planned("not label.lane = 3").where_sql == "1"


def test_two_labels_under_an_and_both_stay_behind() -> None:
    found = planned("label.lane = 3 and label.pump = 7")
    assert found.where_sql == "1"
    assert found.needs_post_filter is True


def test_a_nested_and_pushes_what_it_can() -> None:
    found = planned("unit = a and stream = stderr and label.lane = 3")
    assert found.params == ["a", "stderr"]
    assert found.needs_post_filter is True


def test_the_statement_orders_by_the_corrected_time() -> None:
    assert "ORDER BY COALESCE(corrected_ns, observed_ns) ASC" in planned("").sql()


def test_the_statement_can_select_a_narrower_column_list() -> None:
    assert planned("").sql("id, klass").startswith("SELECT id, klass FROM records")


def test_a_limit_is_pushed_when_nothing_is_left_over() -> None:
    assert planned("unit = a limit 10").sql().endswith("LIMIT 10")


def test_a_limit_is_not_pushed_when_a_post_filter_remains() -> None:
    # The post filter would be handed ten rows and might keep two, which is not
    # what "limit 10" was asked for.
    assert "LIMIT" not in planned("label.lane = 3 limit 10").sql()


def test_a_query_with_no_limit_has_no_limit_clause() -> None:
    assert "LIMIT" not in planned("unit = a").sql()


def test_an_unplannable_node_is_refused() -> None:
    class Odd:
        pass

    with pytest.raises(QueryError, match="cannot be planned"):
        plan(Query(where=Odd()))  # type: ignore[arg-type]
