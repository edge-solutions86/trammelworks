from __future__ import annotations

import json
from pathlib import Path

import pytest

from stanchion.archive.config import ArchiveSettings
from stanchion.archive.repository import Repository
from stanchion.archive.schema import open_archive
from stanchion.config import IngestConfig, SiteConfig
from stanchion.records import LogRecord, Stream
from stanchion.service.app import Request, Service
from stanchion.service.metrics import Gauges, archive_gauges, gather, ship_gauges, spool_gauges
from stanchion.ship.config import ShipSettings
from stanchion.site import state_directory
from stanchion.spool.budget import Budget
from stanchion.spool.config import SpoolSettings
from stanchion.spool.priority import PriorityTable
from stanchion.spool.store import Spool
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-06-10T12:00:00Z")
TABLE = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})


def config_for(tmp_path: Path, **over: object) -> SiteConfig:
    settings: dict[str, object] = {
        "site": "US-TX-0417",
        "ingest": IngestConfig(log_root=(tmp_path / "containers").as_posix()),
        "spool": SpoolSettings(
            directory=(tmp_path / "spool").as_posix(),
            budget_bytes=1_000_000,
            segment_bytes=4096,
            priority=TABLE,
        ),
        "ship": ShipSettings(monthly_allowance_bytes=10_000_000),
        "archive": ArchiveSettings(
            path=(tmp_path / "archive" / "records.sqlite3").as_posix(), max_bytes=500_000
        ),
    }
    settings.update(over)
    return SiteConfig(**settings)  # type: ignore[arg-type]


@pytest.fixture
def config(tmp_path: Path) -> SiteConfig:
    return config_for(tmp_path)


def record(index: int, unit: str = "pump-bridge") -> LogRecord:
    return LogRecord(
        site="US-TX-0417",
        unit=unit,
        stream=Stream.STDOUT,
        observed_at=NanoTime(NOW.epoch_ns + index),
        message=f"line {index} " + "x" * 200,
    )


def fill_spool(config: SiteConfig, count: int, unit: str = "pump-bridge") -> None:
    spool = Spool(
        directory=config.spool.path,
        budget=Budget(limit_bytes=config.spool.budget_bytes),
        table=TABLE,
        segment_bytes=config.spool.segment_bytes,
        compress=False,
    )
    for index in range(count):
        spool.append(record(index, unit))
    spool.seal_all()


def fill_archive(config: SiteConfig, count: int) -> None:
    repo = Repository(connection=open_archive(config.archive.file), table=TABLE)
    repo.add_all([record(index) for index in range(count)], NOW)


def test_an_empty_gauge_set_renders_nothing() -> None:
    assert Gauges().render() == ""


def test_gauges_render_one_pair_per_line() -> None:
    found = Gauges()
    found.set("b_second", 2)
    found.set("a_first", 1)
    assert found.render() == "a_first 1\nb_second 2\n"


def test_gauges_come_back_sorted() -> None:
    found = Gauges()
    found.set("zeta", 1)
    found.set("alpha", 2)
    assert list(found.as_dict()) == ["alpha", "zeta"]


def test_a_missing_area_is_noted_not_raised() -> None:
    found = Gauges()
    found.note_missing("archive")
    assert found.missing == ["archive"]


def test_the_spool_gauges_report_an_empty_spool(config: SiteConfig) -> None:
    found = Gauges()
    spool_gauges(config, found)
    assert found.values["spool_used_bytes"] == 0
    assert found.values["spool_segments"] == 0
    assert found.values["spool_limit_bytes"] == 1_000_000


def test_the_spool_gauges_follow_what_was_written(config: SiteConfig) -> None:
    fill_spool(config, 40)
    found = Gauges()
    spool_gauges(config, found)
    assert found.values["spool_used_bytes"] > 0
    assert found.values["spool_segments"] > 0


def test_the_spool_gauges_split_by_class(config: SiteConfig) -> None:
    fill_spool(config, 20, "payment-kernel")
    found = Gauges()
    spool_gauges(config, found)
    assert found.values["spool_class_bytes_payment"] > 0
    assert found.values["spool_class_bytes_chatter"] == 0


def test_every_class_gets_a_gauge_even_when_empty(config: SiteConfig) -> None:
    found = Gauges()
    spool_gauges(config, found)
    for klass in ("payment", "standard", "chatter"):
        assert f"spool_class_bytes_{klass}" in found.values


def test_the_free_bytes_and_used_bytes_add_up(config: SiteConfig) -> None:
    fill_spool(config, 30)
    found = Gauges()
    spool_gauges(config, found)
    total = found.values["spool_used_bytes"] + found.values["spool_free_bytes"]
    assert total == found.values["spool_limit_bytes"]


def test_the_ship_gauges_report_a_fresh_meter(config: SiteConfig, tmp_path: Path) -> None:
    found = Gauges()
    ship_gauges(config, state_directory(config.spool.directory), found)
    assert found.values["ship_allowance_bytes"] == 10_000_000
    assert found.values["ship_used_bytes"] == 0
    assert found.values["ship_remaining_bytes"] == 10_000_000


def test_the_ship_gauges_report_nothing_outstanding(config: SiteConfig) -> None:
    found = Gauges()
    ship_gauges(config, state_directory(config.spool.directory), found)
    assert found.values["ship_outstanding_records"] == 0
    assert found.values["ship_pending_segments"] == 0


def test_the_archive_gauges_say_when_it_is_off(tmp_path: Path) -> None:
    config = config_for(tmp_path, archive=ArchiveSettings(enabled=False))
    found = Gauges()
    archive_gauges(config, found)
    assert found.values["archive_enabled"] == 0
    assert "archive_records" not in found.values


def test_an_archive_that_has_never_been_written_is_missing_not_broken(
    config: SiteConfig,
) -> None:
    found = Gauges()
    archive_gauges(config, found)
    assert found.values["archive_enabled"] == 1
    assert found.missing == ["archive"]


def test_the_archive_gauges_count_the_records(config: SiteConfig) -> None:
    fill_archive(config, 25)
    found = Gauges()
    archive_gauges(config, found)
    assert found.values["archive_records"] == 25
    assert found.values["archive_bytes"] > 0
    assert found.values["archive_limit_bytes"] == 500_000


def test_gathering_covers_every_area(config: SiteConfig) -> None:
    fill_spool(config, 10)
    fill_archive(config, 10)
    found = gather(config, state_directory(config.spool.directory))
    assert found.values["spool_segments"] > 0
    assert found.values["archive_records"] == 10
    assert found.values["ship_allowance_bytes"] > 0


def test_gathering_reports_how_many_areas_would_not_read(config: SiteConfig) -> None:
    found = gather(config, state_directory(config.spool.directory))
    assert found.values["site_areas_unreadable"] == 1


def test_one_unreadable_area_does_not_stop_the_others(config: SiteConfig) -> None:
    # The archive has never been written. The spool still answers.
    found = gather(config, state_directory(config.spool.directory))
    assert "spool_used_bytes" in found.values


def service_for(config: SiteConfig) -> Service:
    return Service(config=config, now=NOW)


def test_the_metrics_route_answers(config: SiteConfig) -> None:
    answer = service_for(config).handle(Request.of("/metrics"))
    assert answer.status == 200


def test_the_metrics_route_carries_the_site(config: SiteConfig) -> None:
    payload = json.loads(service_for(config).handle(Request.of("/metrics")).body())
    assert payload["site"] == "US-TX-0417"


def test_the_metrics_route_carries_the_gauges(config: SiteConfig) -> None:
    fill_spool(config, 20)
    payload = json.loads(service_for(config).handle(Request.of("/metrics")).body())
    assert payload["gauges"]["spool_segments"] > 0


def test_the_metrics_route_carries_the_text_form(config: SiteConfig) -> None:
    payload = json.loads(service_for(config).handle(Request.of("/metrics")).body())
    lines = payload["text"].strip().splitlines()
    assert all(len(line.split()) == 2 for line in lines)
    assert any(line.startswith("spool_limit_bytes ") for line in lines)


def test_the_text_form_agrees_with_the_gauges(config: SiteConfig) -> None:
    fill_spool(config, 15)
    payload = json.loads(service_for(config).handle(Request.of("/metrics")).body())
    from_text = {
        name: int(value)
        for name, value in (line.split() for line in payload["text"].strip().splitlines())
    }
    assert from_text == payload["gauges"]


def test_the_metrics_route_names_what_it_could_not_read(config: SiteConfig) -> None:
    payload = json.loads(service_for(config).handle(Request.of("/metrics")).body())
    assert payload["unreadable"] == ["archive"]


def test_the_metrics_route_is_still_read_only(config: SiteConfig) -> None:
    answer = service_for(config).handle(Request.of("/metrics", method="POST"))
    assert answer.status == 405


def test_the_metrics_route_honours_a_token(config: SiteConfig) -> None:
    service = Service(config=config, now=NOW, token="hunter2")
    assert service.handle(Request.of("/metrics")).status == 401


def test_the_metrics_route_is_listed_when_a_path_is_wrong(config: SiteConfig) -> None:
    answer = service_for(config).handle(Request.of("/nope"))
    assert "/metrics" in json.loads(answer.body())["routes"]
