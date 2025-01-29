from __future__ import annotations

from datetime import UTC, datetime, timedelta, timezone

import pytest

from stanchion.errors import ConfigError
from stanchion.identity import UnitRef, parse_site, parse_unit, record_key, short_id
from stanchion.records import LogRecord, Stream

FULL_ID = "9f" * 32


def record(**over: object) -> LogRecord:
    base: dict[str, object] = {
        "site": "US-TX-0417",
        "unit": "payment-kernel",
        "stream": Stream.STDOUT,
        "observed_at": datetime(2025, 3, 4, 11, 30, tzinfo=UTC),
        "message": "auth approved",
    }
    base.update(over)
    return LogRecord(**base)  # type: ignore[arg-type]


@pytest.mark.parametrize("raw", ["US-TX-0417", "GB-LDN-0001", "de-by-9999"])
def test_parse_site_accepts_estate_format(raw: str) -> None:
    assert parse_site(raw) == raw.upper()


def test_parse_site_trims_whitespace() -> None:
    assert parse_site("  us-tx-0417\n") == "US-TX-0417"


@pytest.mark.parametrize(
    "raw",
    ["US-TX-417", "USA-TX-0417", "US_TX_0417", "US-TX-04170", "", "US-TX", "US-T-0417"],
)
def test_parse_site_rejects_anything_else(raw: str) -> None:
    with pytest.raises(ConfigError, match="malformed site id"):
        parse_site(raw)


@pytest.mark.parametrize("raw", ["pump-bridge", "tankgauge", "loyalty-sync-2"])
def test_parse_unit_accepts_service_names(raw: str) -> None:
    assert parse_unit(raw) == raw


def test_parse_unit_lowercases() -> None:
    assert parse_unit("Payment-Kernel") == "payment-kernel"


@pytest.mark.parametrize(
    "raw", ["-leading", "trailing-", "double--dash", "9front", "has_score", ""]
)
def test_parse_unit_rejects_anything_else(raw: str) -> None:
    with pytest.raises(ConfigError, match="malformed unit name"):
        parse_unit(raw)


def test_short_id_keeps_twelve_characters() -> None:
    assert short_id(FULL_ID) == "9f9f9f9f9f9f"


def test_short_id_accepts_upper_case_hex() -> None:
    assert short_id(FULL_ID.upper()) == "9f9f9f9f9f9f"


@pytest.mark.parametrize("raw", ["9f" * 31, "9f" * 33, "zz" * 32, ""])
def test_short_id_rejects_a_wrong_length_or_non_hex(raw: str) -> None:
    with pytest.raises(ConfigError, match="malformed container id"):
        short_id(raw)


def test_unit_ref_normalizes_all_three_fields() -> None:
    ref = UnitRef(site=" us-tx-0417 ", unit="Pump-Bridge", container_id=FULL_ID.upper())
    assert ref.site == "US-TX-0417"
    assert ref.unit == "pump-bridge"
    assert ref.container_id == "9f9f9f9f9f9f"
    assert ref.attempt == 0


def test_unit_ref_rejects_a_negative_attempt() -> None:
    with pytest.raises(ConfigError, match="negative restart attempt"):
        UnitRef(site="US-TX-0417", unit="pump-bridge", container_id=FULL_ID, attempt=-1)


def test_unit_ref_slug_is_path_safe() -> None:
    ref = UnitRef(site="US-TX-0417", unit="pump-bridge", container_id=FULL_ID, attempt=4)
    assert ref.slug == "US-TX-0417_pump-bridge_4"
    assert "/" not in ref.slug


def test_unit_ref_str_shows_the_short_id() -> None:
    ref = UnitRef(site="US-TX-0417", unit="pump-bridge", container_id=FULL_ID, attempt=2)
    assert str(ref) == "US-TX-0417/pump-bridge#2(9f9f9f9f9f9f)"


def test_unit_ref_of_two_restarts_differs() -> None:
    first = UnitRef(site="US-TX-0417", unit="pump-bridge", container_id=FULL_ID, attempt=0)
    second = UnitRef(site="US-TX-0417", unit="pump-bridge", container_id=FULL_ID, attempt=1)
    assert first != second
    assert first.slug != second.slug


def test_record_key_is_stable_across_calls() -> None:
    assert record_key(record()) == record_key(record())


def test_record_key_is_hex_of_the_declared_width() -> None:
    key = record_key(record())
    assert len(key) == 32
    assert int(key, 16) >= 0


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("site", "US-TX-0418"),
        ("unit", "tank-gauge"),
        ("stream", Stream.STDERR),
        ("message", "auth declined"),
        ("observed_at", datetime(2025, 3, 4, 11, 30, 1, tzinfo=UTC)),
    ],
)
def test_record_key_changes_with_every_graded_field(field: str, value: object) -> None:
    assert record_key(record(**{field: value})) != record_key(record())


def test_record_key_ignores_the_offset_a_site_writes_in() -> None:
    central = record(observed_at=datetime(2025, 3, 4, 5, 30, tzinfo=timezone(timedelta(hours=-6))))
    assert record_key(central) == record_key(record())


def test_record_key_ignores_labels_and_sources() -> None:
    assert record_key(record().with_labels(lane="3")) == record_key(record())


def test_record_key_separates_fields_so_a_shift_does_not_collide() -> None:
    left = record(unit="pump", message="bridge auth approved")
    right = record(unit="pump-bridge", message="auth approved")
    assert record_key(left) != record_key(right)


def test_record_key_survives_an_encoding_round_trip() -> None:
    original = record(message="prix affýché")
    reloaded = record(message=original.message.encode("utf-8").decode("utf-8"))
    assert record_key(original) == record_key(reloaded)
