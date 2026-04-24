from __future__ import annotations

import json
from datetime import timedelta
from pathlib import Path
from typing import Any

import pytest

from stanchion.errors import ConfigError
from stanchion.ship.config import MeterStore, ShipSettings, ship_settings_from_mapping
from stanchion.timestamps import NanoTime


def test_the_defaults_build() -> None:
    built = ShipSettings()
    assert built.endpoint.startswith("https://")
    assert built.meter().allowance_bytes == 2_000_000_000


def test_a_non_url_endpoint_is_refused() -> None:
    with pytest.raises(ConfigError, match="must be an http or file url"):
        ShipSettings(endpoint="collector.invalid")


def test_a_file_endpoint_is_allowed() -> None:
    built = ShipSettings(endpoint="file:///var/spool/outbox")
    assert built.to_file is True
    assert built.file_directory == Path("/var/spool/outbox")


def test_an_http_endpoint_has_no_directory() -> None:
    with pytest.raises(ConfigError, match="not a directory"):
        assert ShipSettings().file_directory


def test_a_zero_timeout_is_refused() -> None:
    with pytest.raises(ConfigError, match="attempt_timeout must be positive"):
        ShipSettings(attempt_timeout=timedelta(0))


def test_a_zero_interval_is_refused() -> None:
    with pytest.raises(ConfigError, match="interval must be positive"):
        ShipSettings(interval=timedelta(0))


def test_a_batch_bigger_than_the_month_is_refused() -> None:
    with pytest.raises(ConfigError, match="cannot exceed the monthly allowance"):
        ShipSettings(monthly_allowance_bytes=1000, batch_bytes=4000)


def test_a_bad_anchor_day_is_refused_as_a_config_error() -> None:
    with pytest.raises(ConfigError, match="anchor day must be 1 to 28"):
        ShipSettings(anchor_day=31)


def test_a_bad_floor_is_refused_as_a_config_error() -> None:
    with pytest.raises(ConfigError, match=r"floor must be in"):
        ShipSettings(floors={"payment": 1.5})


def test_the_limits_come_from_the_settings() -> None:
    built = ShipSettings(batch_bytes=4096, batch_records=40)
    assert built.limits().max_bytes == 4096
    assert built.limits().max_records == 40


def test_the_floors_reach_the_meter() -> None:
    built = ShipSettings(floors={"chatter": 0.5})
    assert built.meter().floor_for("chatter") == 0.5


def test_the_mapping_builder_reads_written_units() -> None:
    raw: dict[str, Any] = {
        "monthly_allowance": "5GB",
        "batch": "64KiB",
        "attempt_timeout": "1m",
        "interval": "10m",
    }
    built = ship_settings_from_mapping(raw)
    assert built.monthly_allowance_bytes == 5_000_000_000
    assert built.batch_bytes == 65536
    assert built.attempt_timeout == timedelta(minutes=1)
    assert built.interval == timedelta(minutes=10)


def test_an_empty_section_gives_the_defaults() -> None:
    assert ship_settings_from_mapping({}) == ShipSettings()


def test_the_mapping_builder_rejects_an_unknown_key() -> None:
    with pytest.raises(ConfigError, match="unknown ship key"):
        ship_settings_from_mapping({"endpiont": "https://x/y"})


def test_the_mapping_builder_needs_floors_to_be_a_mapping() -> None:
    with pytest.raises(ConfigError, match="the floors section must be a mapping"):
        ship_settings_from_mapping({"floors": ["payment"]})


def test_the_mapping_builder_reads_floors() -> None:
    built = ship_settings_from_mapping({"floors": {"chatter": 0.4}})
    assert built.meter().floor_for("chatter") == 0.4


@pytest.fixture
def store(tmp_path: Path) -> MeterStore:
    return MeterStore(directory=tmp_path / "state")


def test_loading_with_nothing_stored_gives_a_fresh_meter(store: MeterStore) -> None:
    meter = store.load(ShipSettings())
    assert meter.used_bytes == 0
    assert meter.rollovers == 0


def test_a_saved_meter_comes_back(store: MeterStore) -> None:
    meter = ShipSettings().meter()
    meter.roll(NanoTime.parse("2025-03-04T12:00:00Z"))
    meter.charge(4096)
    store.save(meter)
    restored = store.load(ShipSettings())
    assert restored.used_bytes == 4096
    assert restored.started_at == NanoTime.parse("2025-03-01T00:00:00Z")
    assert restored.rollovers == 1


def test_save_creates_the_directory(tmp_path: Path) -> None:
    store = MeterStore(directory=tmp_path / "deep" / "state")
    store.save(ShipSettings().meter())
    assert store.path.exists()


def test_save_leaves_no_temporary_file(store: MeterStore) -> None:
    store.save(ShipSettings().meter())
    assert [item.name for item in store.directory.iterdir()] == ["meter.json"]


def test_the_allowance_always_comes_from_the_config(store: MeterStore) -> None:
    old = ShipSettings(monthly_allowance_bytes=500_000, batch_bytes=1024)
    meter = old.meter()
    meter.roll(NanoTime.parse("2025-03-04T12:00:00Z"))
    meter.charge(500)
    store.save(meter)
    restored = store.load(ShipSettings(monthly_allowance_bytes=9_000_000))
    assert restored.allowance_bytes == 9_000_000
    assert restored.used_bytes == 500


def test_a_corrupt_meter_file_starts_fresh(store: MeterStore) -> None:
    store.save(ShipSettings().meter())
    store.path.write_text("{broken", encoding="utf-8")
    assert store.load(ShipSettings()).used_bytes == 0
    assert store.corrupt == 1


def test_a_meter_file_from_another_version_starts_fresh(store: MeterStore) -> None:
    store.directory.mkdir(parents=True, exist_ok=True)
    store.path.write_text(json.dumps({"version": 9, "used_bytes": 4}), encoding="utf-8")
    assert store.load(ShipSettings()).used_bytes == 0
    assert store.corrupt == 1


def test_a_meter_file_missing_a_field_starts_fresh(store: MeterStore) -> None:
    store.directory.mkdir(parents=True, exist_ok=True)
    store.path.write_text(json.dumps({"version": 1, "used_bytes": 4}), encoding="utf-8")
    assert store.load(ShipSettings()).used_bytes == 0
    assert store.corrupt == 1


def test_the_spend_survives_a_restart_inside_one_period(store: MeterStore) -> None:
    settings = ShipSettings()
    meter = settings.meter()
    meter.roll(NanoTime.parse("2025-03-04T12:00:00Z"))
    meter.charge(1_000_000)
    store.save(meter)
    restored = store.load(settings)
    assert restored.roll(NanoTime.parse("2025-03-05T12:00:00Z")) is False
    assert restored.used_bytes == 1_000_000
