from __future__ import annotations

from datetime import timedelta
from pathlib import Path

import pytest

from stanchion.archive.config import ArchiveSettings, archive_settings_from_mapping
from stanchion.archive.retention import Hold, RetentionPolicy
from stanchion.archive.space import checkpoint, file_size, measure
from stanchion.clocks import ManualClock
from stanchion.config import IngestConfig, SiteConfig
from stanchion.errors import ConfigError
from stanchion.housekeep import HousekeepReport
from stanchion.records import LogRecord, Stream
from stanchion.ship.config import ShipSettings
from stanchion.ship.transport import MemoryTransport
from stanchion.site import Site
from stanchion.spool.config import SpoolSettings
from stanchion.spool.priority import PriorityTable
from stanchion.timestamps import NanoTime
from tests.support.logfiles import SiteTree

NOW = NanoTime.parse("2025-09-01T12:00:00Z")
DAY_NS = 24 * 60 * 60 * 1_000_000_000
TABLE = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})
POLICY = RetentionPolicy(
    windows={
        "payment": timedelta(days=180),
        "standard": timedelta(days=30),
        "chatter": timedelta(days=7),
    }
)


def archive_for(tmp_path: Path, **over: object) -> ArchiveSettings:
    settings: dict[str, object] = {
        "path": (tmp_path / "archive" / "records.sqlite3").as_posix(),
        "retention": POLICY,
        "max_bytes": 200_000,
    }
    settings.update(over)
    return ArchiveSettings(**settings)  # type: ignore[arg-type]


def build(tree: SiteTree, tmp_path: Path, **over: object) -> Site:
    clock = ManualClock()
    clock.set_wall("2025-09-01T12:00:00Z")
    config = SiteConfig(
        site="US-TX-0417",
        ingest=IngestConfig(log_root=tree.root.as_posix()),
        spool=SpoolSettings(
            directory=(tmp_path / "spool").as_posix(),
            budget_bytes=8_000_000,
            segment_bytes=200_000,
            priority=TABLE,
        ),
        ship=ShipSettings(monthly_allowance_bytes=50_000_000),
        archive=archive_for(tmp_path, **over),
    )
    site = Site.build(config=config, transport=MemoryTransport(), clock=clock)
    site.open()
    return site


def load(site: Site, unit: str, count: int, day: int) -> None:
    site.absorb(
        [
            LogRecord(
                site="US-TX-0417",
                unit=unit,
                stream=Stream.STDOUT,
                observed_at=NanoTime(NOW.epoch_ns - (day * DAY_NS) + index),
                message=f"{unit} {day} {index} " + "x" * 300,
            )
            for index in range(count)
        ]
    )


def settle(site: Site) -> None:
    assert site.archive is not None
    checkpoint(site.archive.connection)


def test_a_site_with_no_archive_housekeeps_to_nothing(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, enabled=False)
    assert site.housekeep().idle
    site.close()


def test_a_quiet_archive_is_left_alone(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path)
    load(site, "pump-bridge", 20, day=1)
    settle(site)
    assert site.housekeep().idle
    site.close()


def test_the_sweep_takes_what_retention_allows(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path)
    load(site, "price-sign", 200, day=40)
    settle(site)
    report = site.housekeep()
    assert report.sweep.deleted == 200
    assert report.trim.deleted == 0
    site.close()


def test_nothing_is_trimmed_unless_the_config_says_so(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, max_bytes=50_000)
    load(site, "price-sign", 1500, day=1)
    settle(site)
    report = site.housekeep()
    assert report.trim.deleted == 0
    assert site.archive is not None
    assert site.archive.count() == 1500
    site.close()


def test_the_cap_trims_when_it_is_turned_on(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, max_bytes=100_000, trim_to_cap=True)
    load(site, "price-sign", 1500, day=1)
    settle(site)
    report = site.housekeep()
    assert report.trim.deleted > 0
    site.close()


def test_the_trim_leaves_the_payment_records(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, max_bytes=60_000, trim_to_cap=True)
    load(site, "payment-kernel", 800, day=1)
    load(site, "price-sign", 800, day=1)
    settle(site)
    site.housekeep()
    assert site.archive is not None
    assert site.archive.counts_by_class()["payment"] == 800
    site.close()


def test_a_hold_survives_the_trim(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, max_bytes=60_000, trim_to_cap=True)
    load(site, "price-sign", 1200, day=1)
    settle(site)
    assert site.sweeper is not None
    site.sweeper.holds.place(
        Hold(
            name="dispute-4412",
            reason="chargeback",
            placed_at=NOW,
            covers_from=NanoTime(NOW.epoch_ns - (10 * DAY_NS)),
            covers_until=NOW,
        )
    )
    report = site.housekeep()
    assert report.trim.deleted == 0
    assert report.trim.held > 0
    site.close()


def test_the_vacuum_gives_the_card_its_space_back(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, vacuum_min_reclaim_bytes=1)
    load(site, "price-sign", 3000, day=40)
    settle(site)
    before = file_size(tmp_path / "archive" / "records.sqlite3")
    report = site.housekeep()
    assert report.reclaimed > 0
    assert file_size(tmp_path / "archive" / "records.sqlite3") < before
    site.close()


def test_the_vacuum_can_be_turned_off(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, vacuum=False)
    load(site, "price-sign", 3000, day=40)
    settle(site)
    before = file_size(tmp_path / "archive" / "records.sqlite3")
    report = site.housekeep()
    assert report.reclaimed == 0
    assert file_size(tmp_path / "archive" / "records.sqlite3") == before
    site.close()


def test_a_small_saving_is_not_worth_the_write_cycles(tree: SiteTree, tmp_path: Path) -> None:
    # Most of the file stays, so neither the byte floor nor the fraction says
    # rewrite it. Either one on its own would be enough, which is why the kept
    # records have to outweigh the swept ones for this to mean anything.
    site = build(tree, tmp_path, vacuum_min_reclaim_bytes=1024 * 1024 * 1024)
    load(site, "price-sign", 3000, day=1)
    load(site, "price-sign", 20, day=40)
    settle(site)
    report = site.housekeep()
    assert report.sweep.deleted == 20
    assert report.reclaimed == 0
    site.close()


def test_the_report_measures_both_sides(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, vacuum_min_reclaim_bytes=1)
    load(site, "price-sign", 2000, day=40)
    settle(site)
    report = site.housekeep()
    assert report.before.total > report.after.total
    site.close()


def test_the_report_reads_as_one_line() -> None:
    assert str(HousekeepReport()) == "swept 0, trimmed 0, freed 0 bytes"


def test_the_counters_carry_the_archive_size(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path)
    load(site, "price-sign", 100, day=1)
    settle(site)
    found = site.snapshot()
    assert found["archive_bytes"] > 0
    assert found["archive_limit"] == 200_000
    assert found["archive_used"] <= found["archive_bytes"]
    site.close()


def test_a_trim_is_counted(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, max_bytes=100_000, trim_to_cap=True)
    load(site, "price-sign", 1500, day=1)
    settle(site)
    site.housekeep()
    assert site.snapshot()["archive_trimmed"] > 0
    site.close()


def test_a_vacuum_is_counted(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, vacuum_min_reclaim_bytes=1)
    load(site, "price-sign", 2000, day=40)
    settle(site)
    site.housekeep()
    assert site.snapshot()["archive_vacuumed"] == 1
    site.close()


def test_a_negative_vacuum_floor_is_refused() -> None:
    with pytest.raises(ConfigError, match="cannot be negative"):
        ArchiveSettings(vacuum_min_reclaim_bytes=-1)


def test_the_new_keys_come_out_of_the_config_block() -> None:
    settings = archive_settings_from_mapping(
        {"trim_to_cap": True, "vacuum": False, "vacuum_min_reclaim": "8MiB"}
    )
    assert settings.trim_to_cap is True
    assert settings.vacuum is False
    assert settings.vacuum_min_reclaim_bytes == 8 * 1024 * 1024


def test_an_unknown_archive_key_is_still_refused() -> None:
    with pytest.raises(ConfigError, match="unknown archive key"):
        archive_settings_from_mapping({"vacuum_when": "sunday"})


def test_the_description_says_what_the_cap_is() -> None:
    assert "cap 2GiB" in ArchiveSettings().describe()


def test_the_description_says_when_it_trims() -> None:
    assert "trims to fit" in ArchiveSettings(trim_to_cap=True).describe()


def test_a_disabled_archive_describes_itself_as_off() -> None:
    assert ArchiveSettings(enabled=False).describe() == "archive off"


def test_housekeeping_after_a_vacuum_still_measures(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, vacuum_min_reclaim_bytes=1)
    load(site, "price-sign", 2000, day=40)
    settle(site)
    site.housekeep()
    assert site.archive is not None
    assert measure(site.archive.connection, tmp_path / "archive" / "records.sqlite3").used > 0
    site.close()


def test_a_site_with_an_archive_has_a_housekeeper(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path)
    assert site.housekeeper() is not None
    site.close()


def test_a_site_without_one_has_none(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, enabled=False)
    assert site.housekeeper() is None
    site.close()


def test_the_housekeeper_can_be_driven_on_its_own(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, vacuum_min_reclaim_bytes=1)
    load(site, "price-sign", 2000, day=40)
    settle(site)
    keeper = site.housekeeper()
    assert keeper is not None
    assert keeper.sweep().deleted == 2000
    assert keeper.vacuum() > 0
    site.close()


def test_the_report_totals_both_kinds_of_deletion() -> None:
    report = HousekeepReport()
    report.sweep.deleted = 4
    report.trim.deleted = 3
    assert report.deleted == 7


def test_the_housekeeper_measures_the_same_file(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path)
    keeper = site.housekeeper()
    assert keeper is not None
    assert keeper.path == tmp_path / "archive" / "records.sqlite3"
    assert keeper.footprint().total > 0
    site.close()
