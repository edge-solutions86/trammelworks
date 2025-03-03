from __future__ import annotations

from datetime import timedelta
from typing import Any

import pytest

from stanchion.config import (
    IngestConfig,
    ShipConfig,
    SiteConfig,
    SpoolConfig,
    site_from_mapping,
)
from stanchion.errors import ConfigError


def test_defaults_build_a_usable_site() -> None:
    cfg = SiteConfig(site="US-TX-0417")
    assert cfg.site == "US-TX-0417"
    assert cfg.ingest.log_root == "/var/log/containers"
    assert cfg.spool.budget_bytes == 512 * 1024 * 1024
    assert cfg.ship.max_attempts == 5


def test_site_id_is_normalized() -> None:
    assert SiteConfig(site="us-tx-0417").site == "US-TX-0417"


def test_bad_site_id_is_rejected_at_construction() -> None:
    with pytest.raises(ConfigError, match="malformed site id"):
        SiteConfig(site="shop 4")


def test_ingest_rejects_a_relative_log_root() -> None:
    with pytest.raises(ConfigError, match="must be an absolute path"):
        IngestConfig(log_root="var/log")


def test_ingest_rejects_a_zero_read_chunk() -> None:
    with pytest.raises(ConfigError, match="read_chunk must be positive"):
        IngestConfig(read_chunk=0)


def test_ingest_rejects_a_poll_interval_of_zero() -> None:
    with pytest.raises(ConfigError, match="poll_interval must be positive"):
        IngestConfig(poll_interval=timedelta(0))


def test_ingest_rejects_a_line_cap_below_the_read_chunk() -> None:
    with pytest.raises(ConfigError, match="at least read_chunk"):
        IngestConfig(read_chunk=8192, max_line_bytes=4096)


def test_ingest_normalizes_the_unit_filter() -> None:
    cfg = IngestConfig(units=("Payment-Kernel", "pump-bridge"))
    assert cfg.units == ("payment-kernel", "pump-bridge")


def test_ingest_rejects_a_malformed_unit_filter() -> None:
    with pytest.raises(ConfigError, match="malformed unit name"):
        IngestConfig(units=("pump bridge",))


def test_spool_rejects_a_relative_directory() -> None:
    with pytest.raises(ConfigError, match="must be absolute"):
        SpoolConfig(directory="spool")


def test_spool_rejects_a_segment_larger_than_the_budget() -> None:
    with pytest.raises(ConfigError, match="cannot exceed budget_bytes"):
        SpoolConfig(budget_bytes=1024, segment_bytes=2048)


def test_spool_allows_a_segment_exactly_the_size_of_the_budget() -> None:
    assert SpoolConfig(budget_bytes=4096, segment_bytes=4096).segment_bytes == 4096


@pytest.mark.parametrize("ratio", [-0.1, 1.0, 1.5])
def test_spool_rejects_a_reserve_ratio_outside_the_range(ratio: float) -> None:
    with pytest.raises(ConfigError, match=r"reserve_ratio must be in"):
        SpoolConfig(reserve_ratio=ratio)


def test_spool_reserve_bytes_follows_the_ratio() -> None:
    cfg = SpoolConfig(budget_bytes=1000, segment_bytes=100, reserve_ratio=0.25)
    assert cfg.reserve_bytes == 250


def test_spool_reserve_of_zero_is_allowed() -> None:
    assert SpoolConfig(reserve_ratio=0.0).reserve_bytes == 0


@pytest.mark.parametrize("endpoint", ["collector.invalid", "ftp://host/x", ""])
def test_ship_rejects_a_non_http_endpoint(endpoint: str) -> None:
    with pytest.raises(ConfigError, match="must be an http url"):
        ShipConfig(endpoint=endpoint)


def test_ship_accepts_plain_http_for_a_lab_collector() -> None:
    assert ShipConfig(endpoint="http://10.0.0.9:8080/v1").endpoint.startswith("http://")


def test_ship_rejects_a_batch_bigger_than_the_month() -> None:
    with pytest.raises(ConfigError, match="cannot exceed the monthly allowance"):
        ShipConfig(monthly_allowance_bytes=1024, batch_bytes=4096)


def test_ship_rejects_zero_attempts() -> None:
    with pytest.raises(ConfigError, match="max_attempts must be positive"):
        ShipConfig(max_attempts=0)


def test_ship_rejects_a_zero_timeout() -> None:
    with pytest.raises(ConfigError, match="attempt_timeout must be positive"):
        ShipConfig(attempt_timeout=timedelta(0))


def test_labels_must_have_sane_keys() -> None:
    with pytest.raises(ConfigError, match="malformed label key"):
        SiteConfig(site="US-TX-0417", labels={"has space": "x"})


def test_labels_allow_dashes_and_underscores() -> None:
    cfg = SiteConfig(site="US-TX-0417", labels={"brand_code": "shell", "region-id": "gulf"})
    assert cfg.labels["brand_code"] == "shell"


def test_as_dict_round_trips_through_the_mapping_builder() -> None:
    cfg = SiteConfig(site="US-TX-0417", labels={"brand": "x"})
    payload = cfg.as_dict()
    payload["ingest"]["poll_interval"] = f"{payload['ingest']['poll_interval']}s"
    payload["spool"]["budget"] = payload["spool"].pop("budget_bytes")
    payload["spool"]["segment"] = payload["spool"].pop("segment_bytes")
    payload["ship"]["monthly_allowance"] = payload["ship"].pop("monthly_allowance_bytes")
    payload["ship"]["batch"] = payload["ship"].pop("batch_bytes")
    payload["ship"]["attempt_timeout"] = f"{payload['ship']['attempt_timeout']}s"
    assert site_from_mapping(payload) == cfg


def test_mapping_builder_reads_the_written_spellings() -> None:
    raw: dict[str, Any] = {
        "site": "GB-LDN-0009",
        "ingest": {"poll_interval": "5s", "read_chunk": "32KiB", "max_line_bytes": "2MiB"},
        "spool": {"budget": "1GiB", "segment": "16MiB"},
        "ship": {"monthly_allowance": "5GB", "batch": "512KiB", "attempt_timeout": "1m"},
    }
    cfg = site_from_mapping(raw)
    assert cfg.ingest.poll_interval == timedelta(seconds=5)
    assert cfg.ingest.read_chunk == 32768
    assert cfg.spool.budget_bytes == 1073741824
    assert cfg.ship.monthly_allowance_bytes == 5_000_000_000
    assert cfg.ship.attempt_timeout == timedelta(minutes=1)


def test_mapping_builder_needs_a_site() -> None:
    with pytest.raises(ConfigError, match="no site id"):
        site_from_mapping({"spool": {}})


def test_mapping_builder_rejects_an_unknown_section() -> None:
    with pytest.raises(ConfigError, match="unknown config section"):
        site_from_mapping({"site": "US-TX-0417", "spoool": {}})


def test_mapping_builder_names_every_unknown_section() -> None:
    with pytest.raises(ConfigError) as caught:
        site_from_mapping({"site": "US-TX-0417", "zebra": 1, "alpha": 2})
    assert caught.value.detail == "alpha, zebra"


def test_mapping_builder_stringifies_label_values() -> None:
    cfg = site_from_mapping({"site": "US-TX-0417", "labels": {"lane": 3}})
    assert cfg.labels == {"lane": "3"}


def test_a_windows_drive_path_counts_as_absolute() -> None:
    assert SpoolConfig(directory="C:/spool").directory == "C:/spool"


def test_a_bare_name_is_still_relative() -> None:
    with pytest.raises(ConfigError, match="must be absolute"):
        SpoolConfig(directory="spool/here")
