from __future__ import annotations

from datetime import timedelta
from typing import Any

import pytest

from stanchion.config import IngestConfig, SiteConfig, site_from_mapping
from stanchion.errors import ConfigError


def test_defaults_build_a_usable_site() -> None:
    cfg = SiteConfig(site="US-TX-0417")
    assert cfg.site == "US-TX-0417"
    assert cfg.ingest.log_root == "/var/log/containers"
    assert cfg.spool.budget_bytes == 512 * 1024 * 1024
    assert cfg.spool.directory == "/var/lib/stanchion/spool"
    assert cfg.ship.batch_records == 2000


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
    payload["ship"]["attempt_timeout"] = f"{payload['ship']['attempt_timeout']}s"
    payload["ship"]["interval"] = f"{payload['ship']['interval']}s"
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
    with pytest.raises(ConfigError, match="unknown config key"):
        site_from_mapping({"site": "US-TX-0417", "spoool": {}})


def test_mapping_builder_names_every_unknown_section() -> None:
    with pytest.raises(ConfigError) as caught:
        site_from_mapping({"site": "US-TX-0417", "zebra": 1, "alpha": 2})
    assert caught.value.detail == "alpha, zebra"


def test_mapping_builder_stringifies_label_values() -> None:
    cfg = site_from_mapping({"site": "US-TX-0417", "labels": {"lane": 3}})
    assert cfg.labels == {"lane": "3"}


def test_a_windows_drive_path_counts_as_absolute() -> None:
    assert IngestConfig(log_root="C:/logs").log_root == "C:/logs"


def test_a_bare_name_is_still_relative() -> None:
    with pytest.raises(ConfigError, match="must be an absolute path"):
        IngestConfig(log_root="logs/here")


def test_the_redact_section_defaults_to_on() -> None:
    assert SiteConfig(site="US-TX-0417").redact.enabled is True


def test_a_disabled_pass_is_refused_at_site_construction() -> None:
    from stanchion.redact.config import RedactConfig

    with pytest.raises(ConfigError, match="only be disabled on a lab site"):
        SiteConfig(site="US-TX-0417", redact=RedactConfig(enabled=False))


def test_a_bench_site_may_disable_the_pass() -> None:
    from stanchion.redact.config import RedactConfig

    built = SiteConfig(site="XX-LAB-0001", redact=RedactConfig(enabled=False))
    assert built.redact.enabled is False


def test_the_redact_section_is_read_from_a_mapping() -> None:
    built = site_from_mapping(
        {
            "site": "US-TX-0417",
            "redact": {"keep_first": 0, "rules": {"driver": {"pattern": "driver=(x)"}}},
        }
    )
    assert built.redact.keep_first == 0
    assert built.redact.rule_names == ("driver",)


def test_the_redact_section_appears_in_as_dict() -> None:
    payload = SiteConfig(site="US-TX-0417").as_dict()
    assert payload["redact"]["enabled"] is True
    assert payload["redact"]["rules"] == {}


def test_multiline_rules_are_read_into_ingest() -> None:
    built = site_from_mapping(
        {
            "site": "US-TX-0417",
            "ingest": {"multiline": {"payment-kernel": {"continuation": "^ +"}}},
        }
    )
    assert [rule.unit for rule in built.ingest.multiline] == ["payment-kernel"]


def test_two_multiline_rules_for_one_unit_are_refused() -> None:
    from stanchion.ingest.multiline import MultilineRule

    twice = (
        MultilineRule(unit="pump-bridge", continuation="^ +"),
        MultilineRule(unit="pump-bridge", start="^x"),
    )
    with pytest.raises(ConfigError, match="same unit"):
        IngestConfig(multiline=twice)


def test_an_unknown_top_level_section_is_still_refused() -> None:
    with pytest.raises(ConfigError, match="unknown config key"):
        site_from_mapping({"site": "US-TX-0417", "redactt": {}})
