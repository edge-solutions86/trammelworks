from __future__ import annotations

from datetime import timedelta

import pytest
from hypothesis import given
from hypothesis import strategies as st

from stanchion.errors import ConfigError
from stanchion.units import format_bytes, format_duration, parse_bytes, parse_duration


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("0", 0),
        ("512", 512),
        ("512B", 512),
        ("1KiB", 1024),
        ("1kb", 1000),
        ("1MiB", 1048576),
        ("1MB", 1000000),
        ("512MiB", 536870912),
        ("2GiB", 2147483648),
        ("1TiB", 1099511627776),
        ("1TB", 1000000000000),
    ],
)
def test_parse_bytes_reads_both_suffix_families(raw: str, expected: int) -> None:
    assert parse_bytes(raw) == expected


def test_parse_bytes_treats_binary_and_decimal_as_different() -> None:
    assert parse_bytes("1MiB") != parse_bytes("1MB")
    assert parse_bytes("1MiB") - parse_bytes("1MB") == 48576


def test_parse_bytes_is_case_and_space_insensitive() -> None:
    assert parse_bytes("  4 gib ") == parse_bytes("4GiB")


def test_parse_bytes_accepts_a_fraction_that_lands_on_a_byte() -> None:
    assert parse_bytes("1.5GB") == 1500000000
    assert parse_bytes("0.5KiB") == 512


def test_parse_bytes_rejects_a_fraction_of_a_byte() -> None:
    with pytest.raises(ConfigError, match="whole number of bytes"):
        parse_bytes("1.5B")


def test_parse_bytes_accepts_a_plain_integer() -> None:
    assert parse_bytes(4096) == 4096


def test_parse_bytes_rejects_a_negative_integer() -> None:
    with pytest.raises(ConfigError, match="negative byte size"):
        parse_bytes(-1)


@pytest.mark.parametrize("raw", ["", "MiB", "12 quarts", "1.2.3MB", "-4MiB", "0x40"])
def test_parse_bytes_rejects_junk(raw: str) -> None:
    with pytest.raises(ConfigError):
        parse_bytes(raw)


@pytest.mark.parametrize(
    ("count", "expected"),
    [
        (0, "0B"),
        (1, "1B"),
        (1023, "1023B"),
        (1024, "1KiB"),
        (1536, "1536B"),
        (1048576, "1MiB"),
        (536870912, "512MiB"),
        (1099511627776, "1TiB"),
    ],
)
def test_format_bytes_picks_the_largest_exact_suffix(count: int, expected: str) -> None:
    assert format_bytes(count) == expected


def test_format_bytes_refuses_a_negative_count() -> None:
    with pytest.raises(ConfigError, match="negative byte size"):
        format_bytes(-8)


@given(st.integers(min_value=0, max_value=2**42))
def test_byte_formatting_round_trips(count: int) -> None:
    assert parse_bytes(format_bytes(count)) == count


@pytest.mark.parametrize(
    ("raw", "seconds"),
    [
        ("0s", 0),
        ("30s", 30),
        ("90s", 90),
        ("5m", 300),
        ("2h", 7200),
        ("7d", 604800),
        ("1w", 604800),
        ("250ms", 0.25),
        ("2h30m", 9000),
        ("1d12h", 129600),
        ("1m30s", 90),
        ("45", 45),
    ],
)
def test_parse_duration_reads_compound_spellings(raw: str, seconds: float) -> None:
    assert parse_duration(raw) == timedelta(seconds=seconds)


def test_parse_duration_ignores_internal_spaces() -> None:
    assert parse_duration("2h 30m") == timedelta(hours=2, minutes=30)


def test_parse_duration_accepts_a_bare_number() -> None:
    assert parse_duration(90) == timedelta(seconds=90)
    assert parse_duration(0.5) == timedelta(milliseconds=500)


def test_parse_duration_rejects_a_negative_number() -> None:
    with pytest.raises(ConfigError, match="negative duration"):
        parse_duration(-1)


@pytest.mark.parametrize("raw", ["", "   ", "later", "10y", "5s3", "s", "1h!"])
def test_parse_duration_rejects_junk(raw: str) -> None:
    with pytest.raises(ConfigError):
        parse_duration(raw)


def test_parse_duration_rejects_a_leading_junk_prefix() -> None:
    with pytest.raises(ConfigError, match="malformed duration"):
        parse_duration("about2h")


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        (timedelta(0), "0s"),
        (timedelta(seconds=45), "45s"),
        (timedelta(minutes=1, seconds=30), "1m30s"),
        (timedelta(hours=2, minutes=30), "2h30m"),
        (timedelta(days=1, hours=12), "1d12h"),
        (timedelta(weeks=2), "2w"),
        (timedelta(milliseconds=250), "250ms"),
    ],
)
def test_format_duration_uses_the_compact_spelling(value: timedelta, expected: str) -> None:
    assert format_duration(value) == expected


def test_format_duration_refuses_a_negative_value() -> None:
    with pytest.raises(ConfigError, match="negative duration"):
        format_duration(timedelta(seconds=-1))


@given(st.integers(min_value=0, max_value=60 * 60 * 24 * 400))
def test_duration_formatting_round_trips(seconds: int) -> None:
    assert parse_duration(format_duration(timedelta(seconds=seconds))) == timedelta(seconds=seconds)
