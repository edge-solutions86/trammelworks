from __future__ import annotations

from datetime import timedelta
from pathlib import Path

import pytest

from stanchion.configfile import apply_env, dump_site_config, load_site_config, parse_yaml
from stanchion.errors import ConfigError

SITE_YAML = """
site: US-TX-0417
ingest:
  log_root: /var/log/pods
  poll_interval: 5s
spool:
  budget: 1GiB
  segment: 16MiB
ship:
  endpoint: https://collector.example.net/v1/ingest
  monthly_allowance: 5GB
labels:
  brand: bluebird
"""


def write(tmp_path: Path, text: str) -> Path:
    target = tmp_path / "site.yaml"
    target.write_text(text, encoding="utf-8")
    return target


def test_parse_yaml_returns_a_plain_dict() -> None:
    assert parse_yaml("site: US-TX-0417") == {"site": "US-TX-0417"}


def test_parse_yaml_rejects_a_broken_document() -> None:
    with pytest.raises(ConfigError, match="not valid yaml"):
        parse_yaml("site: [unclosed")


def test_parse_yaml_rejects_an_empty_document() -> None:
    with pytest.raises(ConfigError, match="config file is empty"):
        parse_yaml("\n\n# just a comment\n")


def test_parse_yaml_rejects_a_top_level_list() -> None:
    with pytest.raises(ConfigError, match="must be a mapping"):
        parse_yaml("- site: US-TX-0417")


def test_parse_yaml_will_not_construct_arbitrary_objects() -> None:
    with pytest.raises(ConfigError, match="not valid yaml"):
        parse_yaml("site: !!python/object/apply:os.system ['echo hi']")


def test_load_reads_every_section(tmp_path: Path) -> None:
    cfg = load_site_config(write(tmp_path, SITE_YAML), environ={})
    assert cfg.site == "US-TX-0417"
    assert cfg.ingest.log_root == "/var/log/pods"
    assert cfg.ingest.poll_interval == timedelta(seconds=5)
    assert cfg.spool.budget_bytes == 1073741824
    assert cfg.ship.monthly_allowance_bytes == 5_000_000_000
    assert cfg.labels == {"brand": "bluebird"}


def test_load_reports_a_missing_file(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="config file not found"):
        load_site_config(tmp_path / "absent.yaml", environ={})


def test_load_reports_a_directory_as_unreadable(tmp_path: Path) -> None:
    with pytest.raises(ConfigError):
        load_site_config(tmp_path, environ={})


def test_env_overlay_replaces_a_scalar(tmp_path: Path) -> None:
    cfg = load_site_config(write(tmp_path, SITE_YAML), environ={"STANCHION_SPOOL_BUDGET": "64MiB"})
    assert cfg.spool.budget_bytes == 64 * 1024 * 1024
    assert cfg.spool.segment_bytes == 16 * 1024 * 1024


def test_env_overlay_can_move_the_site(tmp_path: Path) -> None:
    cfg = load_site_config(write(tmp_path, SITE_YAML), environ={"STANCHION_SITE": "GB-LDN-0009"})
    assert cfg.site == "GB-LDN-0009"


def test_env_overlay_rejects_an_unknown_variable() -> None:
    with pytest.raises(ConfigError, match="unknown environment override"):
        apply_env({"site": "US-TX-0417"}, {"STANCHION_SPOOL_BUDGT": "1MiB"})


def test_env_overlay_ignores_unrelated_variables() -> None:
    merged = apply_env({"site": "US-TX-0417"}, {"PATH": "/usr/bin", "HOME": "/root"})
    assert merged == {"site": "US-TX-0417"}


def test_env_overlay_creates_a_missing_section() -> None:
    merged = apply_env({"site": "US-TX-0417"}, {"STANCHION_ENDPOINT": "http://10.0.0.9/v1"})
    assert merged["ship"] == {"endpoint": "http://10.0.0.9/v1"}


def test_env_overlay_does_not_mutate_the_input() -> None:
    raw = {"site": "US-TX-0417", "spool": {"budget": "1GiB"}}
    apply_env(raw, {"STANCHION_SPOOL_BUDGET": "64MiB"})
    assert raw["spool"] == {"budget": "1GiB"}


def test_env_overlay_still_validates(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="malformed site id"):
        load_site_config(write(tmp_path, SITE_YAML), environ={"STANCHION_SITE": "nope"})


def test_load_rejects_an_unknown_section(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="unknown config section"):
        load_site_config(write(tmp_path, "site: US-TX-0417\nshipp: {}\n"), environ={})


def test_dump_round_trips_through_load(tmp_path: Path) -> None:
    original = load_site_config(write(tmp_path, SITE_YAML), environ={})
    rendered = dump_site_config(original)
    assert "site: US-TX-0417" in rendered
    assert "budget: 1073741824" in rendered


def test_dump_is_deterministic(tmp_path: Path) -> None:
    cfg = load_site_config(write(tmp_path, SITE_YAML), environ={})
    assert dump_site_config(cfg) == dump_site_config(cfg)


def test_dump_uses_block_style_so_support_can_read_it(tmp_path: Path) -> None:
    # An empty mapping still renders as {}, which is fine. What must not appear
    # is a flow mapping with anything in it, because that is the form that runs
    # a whole section onto one unreadable line.
    cfg = load_site_config(write(tmp_path, SITE_YAML), environ={})
    rendered = dump_site_config(cfg)
    assert "{}" in rendered
    assert rendered.replace("{}", "").count("{") == 0


def test_a_minimal_config_only_needs_a_site(tmp_path: Path) -> None:
    cfg = load_site_config(write(tmp_path, "site: US-TX-0417\n"), environ={})
    assert cfg.ingest.log_root == "/var/log/containers"
    assert cfg.ship.batch_records == 2000
