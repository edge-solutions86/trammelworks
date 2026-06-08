"""The shipped example has to be a config this build actually accepts.

An example that no longer parses is worse than no example, because it is the
first thing an engineer copies onto a box. So it is loaded here rather than
admired, and every section it names is checked against what came out.
"""

from __future__ import annotations

from datetime import timedelta
from pathlib import Path

import pytest
import yaml

from stanchion.config import SECTIONS, SiteConfig, site_from_mapping
from stanchion.configfile import dump_site_config, load_site_config
from stanchion.doctor import Level, run_checks

EXAMPLE = Path(__file__).resolve().parent.parent / "examples" / "site.yaml"


@pytest.fixture
def raw() -> dict[str, object]:
    return dict(yaml.safe_load(EXAMPLE.read_text(encoding="utf-8")))


@pytest.fixture
def config(raw: dict[str, object]) -> SiteConfig:
    return site_from_mapping(raw)


def test_the_example_is_there() -> None:
    assert EXAMPLE.exists()


def test_the_example_loads_from_disk() -> None:
    assert load_site_config(EXAMPLE).site == "US-TX-0417"


def test_the_example_names_only_sections_this_build_knows(raw: dict[str, object]) -> None:
    assert set(raw) <= SECTIONS


def test_the_example_writes_every_section(raw: dict[str, object]) -> None:
    # A worked example that leaves half the file out teaches half the file.
    assert set(raw) == SECTIONS


def test_the_ingest_section_comes_through(config: SiteConfig) -> None:
    assert config.ingest.log_root == "/var/log/containers"
    assert config.ingest.poll_interval == timedelta(seconds=2)


def test_the_multiline_rules_come_through(config: SiteConfig) -> None:
    units = {rule.unit for rule in config.ingest.multiline}
    assert units == {"payment-kernel", "pump-bridge"}


def test_the_spool_budget_is_read_in_binary_units(config: SiteConfig) -> None:
    assert config.spool.budget_bytes == 512 * 1024 * 1024
    assert config.spool.segment_bytes == 8 * 1024 * 1024


def test_the_allowance_is_read_in_decimal_units(config: SiteConfig) -> None:
    # The plan is sold in decimal gigabytes and the disk is formatted in
    # binary ones. Reading either as the other is off by about five percent
    # on the one number that costs money.
    assert config.ship.monthly_allowance_bytes == 2_000_000_000


def test_the_priority_table_assigns_the_units_it_names(config: SiteConfig) -> None:
    table = config.spool.table()
    assert table.class_for("payment-kernel").name == "payment"
    assert table.class_for("price-sign").name == "chatter"
    assert table.class_for("tank-gauge").name == "standard"


def test_the_retention_windows_come_through(config: SiteConfig) -> None:
    policy = config.archive.retention
    assert policy.window_for("payment") == timedelta(days=180)
    assert policy.window_for("chatter") == timedelta(days=7)


def test_redaction_is_on_with_site_rules(config: SiteConfig) -> None:
    assert config.redact.enabled is True
    assert {rule.name for rule in config.redact.rules} == {"driver_id", "fleet_card_ref"}


def test_the_example_names_a_signing_key_without_holding_one(
    raw: dict[str, object], config: SiteConfig
) -> None:
    # The path is in the file. The secret never is.
    assert config.ship.signing_key_file == "/etc/stanchion/site.key"
    assert "BEGIN" not in EXAMPLE.read_text(encoding="utf-8")


def test_the_example_holds_no_secret_looking_values() -> None:
    body = EXAMPLE.read_text(encoding="utf-8").lower()
    for word in ("password", "secret:", "token:", "api_key"):
        assert word not in body


def test_the_archive_does_not_trim_by_default(config: SiteConfig) -> None:
    # Deleting records retention would have kept is opt in, and the example
    # has to show the safe setting.
    assert config.archive.trim_to_cap is False


def test_the_example_round_trips_through_the_dumper(config: SiteConfig) -> None:
    again = site_from_mapping(dict(yaml.safe_load(dump_site_config(config))))
    assert again.site == config.site
    assert again.spool.budget_bytes == config.spool.budget_bytes
    assert again.ship.monthly_allowance_bytes == config.ship.monthly_allowance_bytes


def test_the_archive_is_not_inside_the_spool(config: SiteConfig) -> None:
    config.archive.check_against_spool(config.spool.directory)


def test_the_doctor_finds_no_configuration_faults(config: SiteConfig) -> None:
    # The paths do not exist on this machine, so filesystem checks will
    # complain. What must not appear is a complaint about the config itself.
    report = run_checks(config)
    wrong = [
        item
        for item in report.findings
        if item.level is Level.FAIL and item.check in {"ship", "signing", "space"}
    ]
    # Only the signing key, which is a real file this machine does not have.
    assert [item.check for item in wrong] == ["signing"]


def test_the_endpoint_is_not_the_placeholder(config: SiteConfig) -> None:
    assert "invalid" not in config.ship.endpoint


def test_the_example_labels_the_site(config: SiteConfig) -> None:
    assert config.labels == {"estate": "southwest", "brand": "forecourt-one"}
