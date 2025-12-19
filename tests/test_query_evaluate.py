from __future__ import annotations

import pytest

from stanchion.archive.repository import StoredRecord
from stanchion.errors import QueryError
from stanchion.query.ast import Compare, Op
from stanchion.query.evaluate import compare_values, count, field_value, matches, run
from stanchion.query.parser import parse_query
from stanchion.records import Stream
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-03-04T12:00:00Z")


def at(raw: str) -> NanoTime:
    return NanoTime.parse(raw)


def record(**over: object) -> StoredRecord:
    base: dict[str, object] = {
        "key": "k",
        "site": "US-TX-0417",
        "unit": "payment-kernel",
        "klass": "payment",
        "stream": Stream.STDOUT,
        "observed_at": at("2025-03-04T11:00:00Z"),
        "corrected_at": None,
        "confidence": "measured",
        "message": "auth approved for lane 3",
        "labels": {"lane": "3"},
        "stored_at": NOW,
    }
    base.update(over)
    return StoredRecord(**base)  # type: ignore[arg-type]


def check(text: str, item: StoredRecord | None = None) -> bool:
    return matches(parse_query(text, NOW).where, item if item is not None else record())


def test_an_empty_filter_matches() -> None:
    assert matches(None, record()) is True


def test_an_equality_matches() -> None:
    assert check("unit = payment-kernel") is True
    assert check("unit = tank-gauge") is False


def test_an_inequality_matches() -> None:
    assert check("unit != tank-gauge") is True
    assert check("unit != payment-kernel") is False


def test_a_contains_match_ignores_case() -> None:
    assert check("message ~ APPROVED") is True
    assert check("message ~ declined") is False


def test_a_negated_contains_match() -> None:
    assert check("message !~ declined") is True
    assert check("message !~ approved") is False


def test_ordering_operators_compare_as_strings() -> None:
    assert compare_values("b", Op.GT, "a") is True
    assert compare_values("a", Op.LT, "b") is True
    assert compare_values("a", Op.LE, "a") is True
    assert compare_values("a", Op.GE, "a") is True


def test_an_unknown_operator_is_refused() -> None:
    with pytest.raises(QueryError, match="unknown operator"):
        compare_values("a", "??", "b")  # type: ignore[arg-type]


def test_the_class_field_reads_the_class() -> None:
    assert check("class = payment") is True
    assert check("class = chatter") is False


def test_the_stream_field_reads_the_stream() -> None:
    assert check("stream = stdout") is True
    assert check("stream = stderr") is False


def test_the_confidence_field_is_readable() -> None:
    assert check("confidence = measured") is True


def test_a_label_is_readable() -> None:
    assert check("label.lane = 3") is True
    assert check("label.lane = 4") is False


def test_an_absent_label_does_not_match_an_equality() -> None:
    assert check("label.pump = 7") is False


def test_an_absent_label_does_match_a_negation() -> None:
    assert check("label.pump != 7") is True
    assert check("label.pump !~ 7") is True


def test_field_value_reads_what_is_there() -> None:
    assert field_value(record(), "unit") == "payment-kernel"
    assert field_value(record(), "label.lane") == "3"
    assert field_value(record(), "label.absent") is None


def test_an_in_list_matches_any_member() -> None:
    assert check("unit in (payment-kernel, tank-gauge)") is True
    assert check("unit in (tank-gauge, price-sign)") is False


def test_an_in_list_on_an_absent_label_does_not_match() -> None:
    assert check("label.pump in (7)") is False


def test_and_needs_both() -> None:
    assert check("unit = payment-kernel and stream = stdout") is True
    assert check("unit = payment-kernel and stream = stderr") is False


def test_or_needs_either() -> None:
    assert check("unit = tank-gauge or stream = stdout") is True
    assert check("unit = tank-gauge or stream = stderr") is False


def test_not_inverts() -> None:
    assert check("not unit = tank-gauge") is True
    assert check("not unit = payment-kernel") is False


def test_a_since_window_uses_the_observed_time() -> None:
    assert check("since 2h") is True
    assert check("since 30m") is False


def test_an_until_window_uses_the_observed_time() -> None:
    assert check("until 30m") is True
    assert check("until 2h") is False


def test_a_window_prefers_the_corrected_time() -> None:
    corrected = record(
        observed_at=at("2025-03-04T09:00:00Z"), corrected_at=at("2025-03-04T11:59:00Z")
    )
    assert check("since 30m", corrected) is True


def test_the_window_boundary_is_inclusive() -> None:
    item = record(observed_at=at("2025-03-04T10:00:00Z"))
    assert check("since 2h", item) is True
    assert check("until 2h", item) is True


def test_a_compound_filter_matches() -> None:
    text = "class = payment and since 2h and not message ~ heartbeat"
    assert check(text) is True


def test_an_unknown_node_type_is_refused() -> None:
    class Odd:
        pass

    with pytest.raises(QueryError, match="unknown filter node"):
        matches(Odd(), record())  # type: ignore[arg-type]


def test_run_filters_a_list() -> None:
    records = [record(message="one"), record(message="two"), record(message="three")]
    found = run(parse_query("message ~ t", NOW), records)
    assert [item.message for item in found] == ["two", "three"]


def test_run_honours_the_limit() -> None:
    records = [record(message=f"line {index}") for index in range(5)]
    assert len(run(parse_query("limit 2", NOW), records)) == 2


def test_run_without_a_limit_returns_everything_matching() -> None:
    records = [record(message=f"line {index}") for index in range(5)]
    assert len(run(parse_query("", NOW), records)) == 5


def test_count_ignores_the_limit() -> None:
    records = [record(message=f"line {index}") for index in range(5)]
    assert count(parse_query("limit 2", NOW), records) == 5


def test_a_compare_node_can_be_evaluated_directly() -> None:
    node = Compare(field="unit", op=Op.EQ, value="payment-kernel")
    assert matches(node, record()) is True
