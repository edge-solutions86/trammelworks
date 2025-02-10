from __future__ import annotations

import pytest

from stanchion.errors import StanchionError
from stanchion.telemetry import Counter, Gauge, Registry, check_name


@pytest.mark.parametrize("name", ["fragments_read", "records", "spool_bytes_in_use", "a1_b2"])
def test_check_name_accepts_snake_case(name: str) -> None:
    assert check_name(name) == name


@pytest.mark.parametrize(
    "name", ["Fragments", "fragments-read", "_leading", "trailing_", "9lives", "", "double__score"]
)
def test_check_name_rejects_anything_else(name: str) -> None:
    with pytest.raises(StanchionError, match="malformed metric name"):
        check_name(name)


def test_counter_starts_at_zero() -> None:
    assert Counter("fragments_read").value == 0


def test_counter_increments_by_one_by_default() -> None:
    counter = Counter("fragments_read")
    counter.inc()
    counter.inc()
    assert counter.value == 2


def test_counter_increments_by_an_amount() -> None:
    counter = Counter("bytes_shipped")
    counter.inc(4096)
    assert counter.value == 4096


def test_counter_accepts_a_zero_increment() -> None:
    counter = Counter("bytes_shipped")
    counter.inc(0)
    assert counter.value == 0


def test_counter_refuses_to_go_down() -> None:
    with pytest.raises(StanchionError, match="cannot decrease"):
        Counter("bytes_shipped").inc(-1)


def test_gauge_can_be_set_and_moved() -> None:
    gauge = Gauge("spool_bytes")
    gauge.set(1000)
    gauge.add(-400)
    assert gauge.value == 600


def test_gauge_may_go_negative_because_a_delta_can_be_wrong() -> None:
    gauge = Gauge("spool_bytes")
    gauge.add(-5)
    assert gauge.value == -5


def test_registry_returns_the_same_counter_for_a_repeated_name() -> None:
    registry = Registry()
    first = registry.counter("fragments_read")
    first.inc(3)
    assert registry.counter("fragments_read") is first
    assert registry.counter("fragments_read").value == 3


def test_registry_returns_the_same_gauge_for_a_repeated_name() -> None:
    registry = Registry()
    assert registry.gauge("spool_bytes") is registry.gauge("spool_bytes")


def test_registry_will_not_let_a_name_be_both_kinds() -> None:
    registry = Registry()
    registry.counter("records")
    with pytest.raises(StanchionError, match="already a counter"):
        registry.gauge("records")


def test_registry_will_not_let_a_gauge_become_a_counter() -> None:
    registry = Registry()
    registry.gauge("spool_bytes")
    with pytest.raises(StanchionError, match="already a gauge"):
        registry.counter("spool_bytes")


def test_registry_validates_names() -> None:
    with pytest.raises(StanchionError, match="malformed metric name"):
        Registry().counter("Fragments Read")


def test_incr_is_a_shortcut_for_counter_inc() -> None:
    registry = Registry()
    registry.incr("records")
    registry.incr("records", 4)
    assert registry.snapshot()["records"] == 5


def test_snapshot_merges_both_kinds_and_sorts() -> None:
    registry = Registry()
    registry.incr("zebra_total", 1)
    registry.gauge("alpha_bytes").set(9)
    assert list(registry.snapshot()) == ["alpha_bytes", "zebra_total"]


def test_snapshot_is_a_copy() -> None:
    registry = Registry()
    registry.incr("records")
    snap = registry.snapshot()
    snap["records"] = 99
    assert registry.snapshot()["records"] == 1


def test_render_is_one_pair_per_line() -> None:
    registry = Registry()
    registry.incr("records", 2)
    registry.gauge("spool_bytes").set(4096)
    assert registry.render() == "records 2\nspool_bytes 4096"


def test_render_of_an_empty_registry_is_empty() -> None:
    assert Registry().render() == ""


def test_reset_clears_everything() -> None:
    registry = Registry()
    registry.incr("records")
    registry.gauge("spool_bytes").set(1)
    registry.reset()
    assert registry.snapshot() == {}


def test_two_registries_do_not_share_state() -> None:
    left, right = Registry(), Registry()
    left.incr("records", 5)
    assert right.snapshot() == {}
