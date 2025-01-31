from __future__ import annotations

from datetime import UTC, datetime, timedelta, timezone

import pytest
from hypothesis import given
from hypothesis import strategies as st

from stanchion.errors import ParseError
from stanchion.timestamps import NS_PER_SECOND, NanoTime

EPOCH = "1970-01-01T00:00:00Z"


def test_epoch_is_zero() -> None:
    assert NanoTime.parse(EPOCH).epoch_ns == 0


def test_parse_keeps_all_nine_fractional_digits() -> None:
    value = NanoTime.parse("2025-03-04T11:30:00.123456789Z")
    assert value.nanos_in_second == 123456789


def test_parse_pads_a_short_fraction_on_the_right() -> None:
    assert NanoTime.parse("2025-03-04T11:30:00.5Z").nanos_in_second == 500000000
    assert NanoTime.parse("2025-03-04T11:30:00.000001Z").nanos_in_second == 1000


def test_parse_accepts_no_fraction() -> None:
    assert NanoTime.parse("2025-03-04T11:30:00Z").nanos_in_second == 0


def test_parse_accepts_a_lowercase_marker_and_a_space_separator() -> None:
    left = NanoTime.parse("2025-03-04t11:30:00z")
    right = NanoTime.parse("2025-03-04 11:30:00Z")
    assert left == right == NanoTime.parse("2025-03-04T11:30:00Z")


@pytest.mark.parametrize("zone", ["-06:00", "-0600"])
def test_parse_applies_a_negative_offset(zone: str) -> None:
    assert NanoTime.parse(f"2025-03-04T05:30:00{zone}") == NanoTime.parse("2025-03-04T11:30:00Z")


def test_parse_applies_a_positive_offset() -> None:
    assert NanoTime.parse("2025-03-04T13:30:00+02:00") == NanoTime.parse("2025-03-04T11:30:00Z")


def test_parse_keeps_the_fraction_when_an_offset_is_applied() -> None:
    value = NanoTime.parse("2025-03-04T05:30:00.000000042-06:00")
    assert value.nanos_in_second == 42
    assert value.to_datetime().hour == 11


@pytest.mark.parametrize(
    "raw",
    [
        "",
        "2025-03-04",
        "2025-03-04T11:30:00",
        "2025-03-04T11:30:00.1234567890Z",
        "not a time",
        "2025-03-04T11:30:00+2:00",
    ],
)
def test_parse_rejects_malformed_input(raw: str) -> None:
    with pytest.raises(ParseError, match="malformed rfc3339"):
        NanoTime.parse(raw)


@pytest.mark.parametrize("raw", ["2025-13-04T11:30:00Z", "2025-02-30T11:30:00Z"])
def test_parse_rejects_an_impossible_date(raw: str) -> None:
    with pytest.raises(ParseError, match="impossible rfc3339"):
        NanoTime.parse(raw)


def test_parse_trims_surrounding_whitespace() -> None:
    assert NanoTime.parse("  2025-03-04T11:30:00Z\n") == NanoTime.parse("2025-03-04T11:30:00Z")


def test_from_datetime_keeps_the_microsecond() -> None:
    source = datetime(2025, 3, 4, 11, 30, 0, 123456, tzinfo=UTC)
    assert NanoTime.from_datetime(source).nanos_in_second == 123456000


def test_from_datetime_honours_the_offset() -> None:
    central = datetime(2025, 3, 4, 5, 30, tzinfo=timezone(timedelta(hours=-6)))
    assert NanoTime.from_datetime(central) == NanoTime.parse("2025-03-04T11:30:00Z")


def test_from_datetime_refuses_a_naive_value() -> None:
    with pytest.raises(ParseError, match="naive datetime"):
        NanoTime.from_datetime(datetime(2025, 3, 4, 11, 30))


def test_from_micros_matches_the_journald_convention() -> None:
    assert NanoTime.from_micros(1_741_087_800_000_000) == NanoTime.parse("2025-03-04T11:30:00Z")


def test_seconds_and_nanos_split_the_instant() -> None:
    value = NanoTime.parse("2025-03-04T11:30:00.123456789Z")
    assert value.seconds * NS_PER_SECOND + value.nanos_in_second == value.epoch_ns


def test_to_datetime_truncates_rather_than_rounds() -> None:
    value = NanoTime.parse("2025-03-04T11:30:00.123456999Z")
    assert value.to_datetime().microsecond == 123456


def test_to_datetime_is_utc_aware() -> None:
    assert NanoTime.parse("2025-03-04T11:30:00Z").to_datetime().tzinfo is UTC


def test_isoformat_always_shows_nine_digits() -> None:
    assert NanoTime.parse("2025-03-04T11:30:00Z").isoformat() == "2025-03-04T11:30:00.000000000Z"
    assert NanoTime(42).isoformat() == "1970-01-01T00:00:00.000000042Z"


def test_str_is_the_isoformat() -> None:
    value = NanoTime.parse("2025-03-04T11:30:00.5Z")
    assert str(value) == value.isoformat()


def test_ordering_survives_below_the_microsecond() -> None:
    early = NanoTime.parse("2025-03-04T11:30:00.123456001Z")
    late = NanoTime.parse("2025-03-04T11:30:00.123456002Z")
    assert early < late
    assert sorted([late, early]) == [early, late]
    assert early.to_datetime() == late.to_datetime()


def test_subtraction_gives_nanoseconds() -> None:
    early = NanoTime.parse("2025-03-04T11:30:00Z")
    late = NanoTime.parse("2025-03-04T11:30:01.5Z")
    assert late - early == 1_500_000_000
    assert early - late == -1_500_000_000


def test_shifted_moves_by_a_signed_nanosecond_delta() -> None:
    base = NanoTime.parse("2025-03-04T11:30:00Z")
    assert base.shifted(1).nanos_in_second == 1
    assert base.shifted(-1) < base


def test_nanotime_is_hashable() -> None:
    assert len({NanoTime(7), NanoTime(7), NanoTime(8)}) == 2


@given(st.integers(min_value=0, max_value=4_000_000_000 * NS_PER_SECOND))
def test_isoformat_round_trips(epoch_ns: int) -> None:
    value = NanoTime(epoch_ns)
    assert NanoTime.parse(value.isoformat()) == value
