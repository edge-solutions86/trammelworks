from __future__ import annotations

import pytest

from stanchion import VERSION, version_string
from stanchion.errors import (
    ConfigError,
    IngestError,
    ParseError,
    QueryError,
    RedactionError,
    RetentionError,
    ShipError,
    SpoolError,
    StanchionError,
)

SUBCLASSES = [
    ConfigError,
    IngestError,
    ParseError,
    QueryError,
    RedactionError,
    RetentionError,
    ShipError,
    SpoolError,
]


def test_base_carries_message_and_code() -> None:
    err = StanchionError("pump bridge went away")
    assert err.message == "pump bridge went away"
    assert err.code == "stanchion.error"
    assert err.detail is None
    assert str(err) == "pump bridge went away"


def test_detail_is_appended_to_str() -> None:
    err = StanchionError("spool is full", detail="segment 41")
    assert str(err) == "spool is full (segment 41)"


def test_as_dict_omits_absent_detail() -> None:
    assert StanchionError("nope").as_dict() == {"code": "stanchion.error", "message": "nope"}


def test_as_dict_includes_detail() -> None:
    payload = ConfigError("bad site id", detail="empty string").as_dict()
    assert payload == {
        "code": "stanchion.config",
        "message": "bad site id",
        "detail": "empty string",
    }


@pytest.mark.parametrize("cls", SUBCLASSES)
def test_every_subclass_descends_from_the_base(cls: type[StanchionError]) -> None:
    assert issubclass(cls, StanchionError)
    assert issubclass(cls, Exception)


@pytest.mark.parametrize("cls", SUBCLASSES)
def test_every_subclass_has_a_distinct_code(cls: type[StanchionError]) -> None:
    assert cls.code != StanchionError.code
    assert cls.code.startswith("stanchion.")


def test_codes_are_unique_across_the_hierarchy() -> None:
    codes = [cls.code for cls in SUBCLASSES]
    assert len(set(codes)) == len(codes)


def test_parse_error_records_source_and_offset() -> None:
    err = ParseError("not a cri line", source="/var/log/pods/a.log", offset=4096)
    assert err.source == "/var/log/pods/a.log"
    assert err.offset == 4096
    assert err.as_dict()["offset"] == "4096"
    assert err.as_dict()["source"] == "/var/log/pods/a.log"


def test_parse_error_offset_zero_is_still_reported() -> None:
    err = ParseError("empty file", offset=0)
    assert err.as_dict()["offset"] == "0"


def test_parse_error_without_position_omits_the_keys() -> None:
    payload = ParseError("bad frame").as_dict()
    assert "source" not in payload
    assert "offset" not in payload


def test_errors_can_be_raised_and_caught_as_the_base() -> None:
    with pytest.raises(StanchionError) as caught:
        raise SpoolError("out of room", detail="budget 512 MiB")
    assert caught.value.code == "stanchion.spool"


def test_version_string_is_stable() -> None:
    assert version_string() == f"stanchion {VERSION}"
    assert VERSION.count(".") == 2
