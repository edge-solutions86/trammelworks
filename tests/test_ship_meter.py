from __future__ import annotations

import pytest

from stanchion.errors import ShipError
from stanchion.ship.meter import (
    Meter,
    check_anchor,
    period_end,
    period_start,
)
from stanchion.timestamps import NanoTime


def at(raw: str) -> NanoTime:
    return NanoTime.parse(raw)


def meter(**over: object) -> Meter:
    base: dict[str, object] = {"allowance_bytes": 1000, "anchor_day": 14}
    base.update(over)
    return Meter(**base)  # type: ignore[arg-type]


@pytest.mark.parametrize("day", [1, 14, 28])
def test_a_sane_anchor_day_is_accepted(day: int) -> None:
    assert check_anchor(day) == day


@pytest.mark.parametrize("day", [0, 29, 31, -1])
def test_an_anchor_day_no_month_has_is_refused(day: int) -> None:
    with pytest.raises(ShipError, match="anchor day must be 1 to 28"):
        check_anchor(day)


def test_the_period_starts_on_the_anchor_day() -> None:
    assert period_start(at("2025-03-20T12:00:00Z"), 14) == at("2025-03-14T00:00:00Z")


def test_a_date_before_the_anchor_belongs_to_the_previous_period() -> None:
    assert period_start(at("2025-03-05T12:00:00Z"), 14) == at("2025-02-14T00:00:00Z")


def test_the_anchor_day_itself_starts_the_new_period() -> None:
    assert period_start(at("2025-03-14T00:00:00Z"), 14) == at("2025-03-14T00:00:00Z")


def test_january_rolls_back_into_december() -> None:
    assert period_start(at("2025-01-05T12:00:00Z"), 14) == at("2024-12-14T00:00:00Z")


def test_the_period_ends_one_month_on() -> None:
    assert period_end(at("2025-03-14T00:00:00Z"), 14) == at("2025-04-14T00:00:00Z")


def test_december_ends_in_january() -> None:
    assert period_end(at("2025-12-14T00:00:00Z"), 14) == at("2026-01-14T00:00:00Z")


def test_a_fresh_meter_has_the_whole_allowance() -> None:
    assert meter().remaining == 1000


def test_a_non_positive_allowance_is_refused() -> None:
    with pytest.raises(ShipError, match="allowance must be positive"):
        meter(allowance_bytes=0)


def test_a_floor_outside_the_range_is_refused() -> None:
    with pytest.raises(ShipError, match=r"floor must be in"):
        meter(floors={"payment": 1.0})


def test_charging_spends_the_allowance() -> None:
    built = meter()
    built.charge(300)
    assert built.used_bytes == 300
    assert built.remaining == 700


def test_charging_a_negative_size_is_refused() -> None:
    with pytest.raises(ShipError, match="negative size"):
        meter().charge(-1)


def test_remaining_never_goes_below_zero() -> None:
    built = meter()
    built.charge(5000)
    assert built.remaining == 0


def test_the_spent_ratio_follows_the_charges() -> None:
    built = meter()
    built.charge(250)
    assert built.spent_ratio == 0.25


def test_payment_may_spend_the_whole_allowance() -> None:
    built = meter()
    built.charge(900)
    assert built.allows(100, "payment") is True


def test_chatter_stops_at_its_floor() -> None:
    built = meter()
    built.charge(600)
    assert built.available_to("chatter") == 100
    assert built.allows(200, "chatter") is False


def test_standard_has_a_smaller_floor_than_chatter() -> None:
    built = meter()
    built.charge(600)
    assert built.available_to("standard") > built.available_to("chatter")


def test_the_floor_leaves_room_for_payment_after_chatter_stops() -> None:
    built = meter()
    built.charge(750)
    assert built.allows(100, "chatter") is False
    assert built.allows(100, "payment") is True


def test_an_unknown_class_has_no_floor() -> None:
    built = meter()
    built.charge(900)
    assert built.allows(100, "brand-new") is True


def test_a_negative_size_is_refused_by_allows() -> None:
    with pytest.raises(ShipError, match="negative size"):
        meter().allows(-1)


def test_a_send_exactly_the_size_of_what_is_left_is_allowed() -> None:
    built = meter()
    built.charge(900)
    assert built.allows(100, "payment") is True
    assert built.allows(101, "payment") is False


def test_rolling_starts_a_new_period() -> None:
    built = meter()
    assert built.roll(at("2025-03-20T12:00:00Z")) is True
    assert built.started_at == at("2025-03-14T00:00:00Z")


def test_rolling_twice_in_one_period_does_nothing() -> None:
    built = meter()
    built.roll(at("2025-03-20T12:00:00Z"))
    built.charge(500)
    assert built.roll(at("2025-03-25T12:00:00Z")) is False
    assert built.used_bytes == 500


def test_crossing_the_anchor_clears_the_usage() -> None:
    built = meter()
    built.roll(at("2025-03-20T12:00:00Z"))
    built.charge(500)
    assert built.roll(at("2025-04-14T00:00:01Z")) is True
    assert built.used_bytes == 0


def test_the_rollover_count_grows() -> None:
    built = meter()
    built.roll(at("2025-03-20T12:00:00Z"))
    built.roll(at("2025-04-20T12:00:00Z"))
    assert built.rollovers == 2


def test_the_period_end_follows_the_start() -> None:
    built = meter()
    built.roll(at("2025-03-20T12:00:00Z"))
    assert built.ends_at() == at("2025-04-14T00:00:00Z")


def test_a_late_reading_does_not_roll_backwards() -> None:
    built = meter()
    built.roll(at("2025-04-20T12:00:00Z"))
    built.charge(400)
    assert built.roll(at("2025-03-20T12:00:00Z")) is False
    assert built.used_bytes == 400


def test_describe_reads_as_one_line() -> None:
    built = meter()
    built.charge(250)
    assert built.describe() == "meter 250/1000 bytes (25.0%) this period"
