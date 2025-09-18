from __future__ import annotations

from typing import Any

import pytest

from stanchion.errors import ConfigError
from stanchion.spool.config import SpoolSettings, spool_settings_from_mapping


def test_the_defaults_are_consistent() -> None:
    built = SpoolSettings()
    assert built.budget().usable_bytes > built.segment_bytes


def test_the_budget_object_carries_the_reserve() -> None:
    built = SpoolSettings(budget_bytes=1000, segment_bytes=100, reserve_ratio=0.2)
    assert built.budget().reserve_bytes == 200
    assert built.budget().usable_bytes == 800


def test_a_segment_larger_than_usable_is_refused() -> None:
    with pytest.raises(ConfigError, match="does not fit inside the usable budget"):
        SpoolSettings(budget_bytes=1000, segment_bytes=900, reserve_ratio=0.2)


def test_the_refusal_names_both_sizes() -> None:
    with pytest.raises(ConfigError) as caught:
        SpoolSettings(budget_bytes=1024, segment_bytes=1024, reserve_ratio=0.5)
    assert caught.value.detail is not None
    assert "segment" in caught.value.detail
    assert "usable" in caught.value.detail


def test_a_segment_exactly_the_usable_size_is_allowed() -> None:
    assert SpoolSettings(budget_bytes=1000, segment_bytes=800, reserve_ratio=0.2).segment_bytes


def test_a_zero_segment_is_refused() -> None:
    with pytest.raises(ConfigError, match="segment size must be positive"):
        SpoolSettings(segment_bytes=0)


def test_a_zero_budget_is_refused() -> None:
    with pytest.raises(ConfigError, match="budget must be positive"):
        SpoolSettings(budget_bytes=0)


def test_a_reserve_of_one_is_refused() -> None:
    with pytest.raises(ConfigError, match=r"reserve ratio must be in"):
        SpoolSettings(reserve_ratio=1.0)


def test_a_negative_sync_interval_is_refused() -> None:
    with pytest.raises(ConfigError, match="sync_every cannot be negative"):
        SpoolSettings(sync_every=-1)


def test_the_default_priority_table_is_used_when_none_is_given() -> None:
    assert SpoolSettings().table().ship_order()[0] == "payment"


def test_the_path_is_a_path() -> None:
    assert SpoolSettings(directory="/var/lib/x").path.name == "x"


def test_describe_reads_in_human_units() -> None:
    built = SpoolSettings(budget_bytes=1024 * 1024, segment_bytes=64 * 1024)
    line = built.describe()
    assert "1MiB" in line
    assert "64KiB" in line


def test_the_mapping_builder_reads_written_sizes() -> None:
    raw: dict[str, Any] = {"budget": "1GiB", "segment": "16MiB", "reserve_ratio": 0.05}
    built = spool_settings_from_mapping(raw)
    assert built.budget_bytes == 1073741824
    assert built.segment_bytes == 16777216
    assert built.reserve_ratio == 0.05


def test_an_empty_section_gives_the_defaults() -> None:
    assert spool_settings_from_mapping({}) == SpoolSettings()


def test_the_mapping_builder_rejects_an_unknown_key() -> None:
    with pytest.raises(ConfigError, match="unknown spool key"):
        spool_settings_from_mapping({"budgett": "1GiB"})


def test_the_mapping_builder_reads_a_priority_section() -> None:
    raw: dict[str, Any] = {"priority": {"units": {"payment-kernel": "payment"}}}
    built = spool_settings_from_mapping(raw)
    assert built.table().class_for("payment-kernel").name == "payment"


def test_a_priority_section_that_is_not_a_mapping_is_refused() -> None:
    with pytest.raises(ConfigError, match="priority section must be a mapping"):
        spool_settings_from_mapping({"priority": ["payment"]})


def test_compression_can_be_turned_off() -> None:
    assert spool_settings_from_mapping({"compress": False}).compress is False


def test_an_inconsistent_section_is_caught_at_build_time() -> None:
    with pytest.raises(ConfigError, match="does not fit"):
        spool_settings_from_mapping({"budget": "1MiB", "segment": "1MiB"})


def test_a_unit_in_an_unknown_class_is_caught_at_build_time() -> None:
    with pytest.raises(ConfigError, match="unknown class"):
        spool_settings_from_mapping({"priority": {"units": {"pump-bridge": "urgent"}}})
