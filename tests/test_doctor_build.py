"""The version promise, checked rather than asserted in a comment.

From 1.0.0 the two format numbers are what a version means on a box nobody logs
into: a site can be upgraded in place and still read what the previous build
left. These tests cover the check that says whether that held on a real disk.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from stanchion.archive.config import ArchiveSettings
from stanchion.archive.schema import SCHEMA_VERSION, open_archive
from stanchion.config import IngestConfig, SiteConfig
from stanchion.doctor import Level, Report, check_build, run_checks, spool_formats
from stanchion.records import LogRecord, Stream
from stanchion.spool.budget import Budget
from stanchion.spool.config import SpoolSettings
from stanchion.spool.frame import MAGIC
from stanchion.spool.segment import list_segments
from stanchion.spool.store import Spool
from stanchion.timestamps import NanoTime
from stanchion.version import SPOOL_FORMAT, VERSION

NOW = NanoTime.parse("2025-06-10T12:00:00Z")


def config_for(tmp_path: Path, **over: object) -> SiteConfig:
    settings: dict[str, object] = {
        "site": "US-TX-0417",
        "ingest": IngestConfig(log_root=(tmp_path / "containers").as_posix()),
        "spool": SpoolSettings(directory=(tmp_path / "spool").as_posix()),
        "archive": ArchiveSettings(path=(tmp_path / "archive" / "records.sqlite3").as_posix()),
    }
    settings.update(over)
    return SiteConfig(**settings)  # type: ignore[arg-type]


@pytest.fixture
def config(tmp_path: Path) -> SiteConfig:
    return config_for(tmp_path)


def fill_spool(config: SiteConfig, count: int = 5) -> None:
    spool = Spool(
        directory=config.spool.path,
        budget=Budget(limit_bytes=config.spool.budget_bytes),
        segment_bytes=4096,
        compress=False,
    )
    for index in range(count):
        spool.append(
            LogRecord(
                site="US-TX-0417",
                unit="pump-bridge",
                stream=Stream.STDOUT,
                observed_at=NanoTime(NOW.epoch_ns + index),
                message=f"line {index}",
            )
        )
    spool.seal_all()


def restamp(config: SiteConfig, value: int) -> None:
    """Rewrite the format byte of every segment, as another build would have."""
    for info in list_segments(config.spool.path):
        raw = bytearray(info.path.read_bytes())
        raw[2] = value
        info.path.write_bytes(bytes(raw))


def findings(report: Report, level: Level) -> list[str]:
    return [item.message for item in report.findings if item.level is level]


def test_an_empty_spool_has_no_formats(config: SiteConfig) -> None:
    assert spool_formats(config.spool.path) == set()


def test_a_spool_that_is_not_there_has_no_formats(tmp_path: Path) -> None:
    assert spool_formats(tmp_path / "absent") == set()


def test_the_written_segments_are_in_this_format(config: SiteConfig) -> None:
    fill_spool(config)
    assert spool_formats(config.spool.path) == {SPOOL_FORMAT}


def test_a_segment_that_is_not_one_is_ignored(config: SiteConfig) -> None:
    fill_spool(config)
    stray = config.spool.path / "000000009999-standard.spool"
    stray.write_bytes(b"nothing like a frame")
    assert spool_formats(config.spool.path) == {SPOOL_FORMAT}


def test_a_truncated_segment_is_ignored(config: SiteConfig) -> None:
    fill_spool(config)
    stray = config.spool.path / "000000009998-standard.spool"
    stray.write_bytes(MAGIC[:1])
    assert spool_formats(config.spool.path) == {SPOOL_FORMAT}


def test_the_build_is_reported(config: SiteConfig) -> None:
    report = Report()
    check_build(config, report)
    assert any(VERSION in message for message in findings(report, Level.NOTE))


def test_a_matching_spool_is_not_a_problem(config: SiteConfig) -> None:
    fill_spool(config)
    report = Report()
    check_build(config, report)
    assert findings(report, Level.FAIL) == []


def test_a_spool_from_a_newer_build_fails(config: SiteConfig) -> None:
    # The dangerous case. Those segments will not decode, the shipper
    # quarantines them one at a time, and the site looks like it is working
    # while it drops a shift.
    fill_spool(config)
    restamp(config, SPOOL_FORMAT + 1)
    report = Report()
    check_build(config, report)
    assert any("this build writes" in message for message in findings(report, Level.FAIL))


def test_a_spool_from_an_older_build_is_allowed(config: SiteConfig) -> None:
    # Reading what an older build wrote is the promise, so this must pass.
    fill_spool(config)
    restamp(config, max(SPOOL_FORMAT - 1, 0))
    report = Report()
    check_build(config, report)
    assert findings(report, Level.FAIL) == []


def test_an_archive_that_has_never_been_written_is_not_a_fault(tmp_path: Path) -> None:
    config = config_for(tmp_path)
    report = Report()
    check_build(config, report)
    assert findings(report, Level.FAIL) == []


def test_a_current_archive_is_reported_as_current(tmp_path: Path) -> None:
    config = config_for(tmp_path)
    open_archive(config.archive.file).close()
    report = Report()
    check_build(config, report)
    assert any(
        f"archive schema {SCHEMA_VERSION}" in message for message in findings(report, Level.NOTE)
    )


def test_an_archive_from_a_newer_build_fails(tmp_path: Path) -> None:
    config = config_for(tmp_path)
    connection = open_archive(config.archive.file)
    with connection:
        connection.execute(
            "INSERT OR REPLACE INTO meta (name, value) VALUES ('schema_version', ?)",
            (str(SCHEMA_VERSION + 1),),
        )
    connection.close()
    report = Report()
    check_build(config, report)
    assert any("this build writes" in message for message in findings(report, Level.FAIL))


def test_an_archive_awaiting_an_upgrade_only_warns(tmp_path: Path) -> None:
    config = config_for(tmp_path)
    connection = open_archive(config.archive.file)
    with connection:
        connection.execute(
            "INSERT OR REPLACE INTO meta (name, value) VALUES ('schema_version', '1')"
        )
    connection.close()
    report = Report()
    check_build(config, report)
    assert findings(report, Level.FAIL) == []
    assert any("upgrades to" in message for message in findings(report, Level.WARN))


def test_a_site_that_keeps_no_archive_still_reports_its_build(tmp_path: Path) -> None:
    config = config_for(tmp_path, archive=ArchiveSettings(enabled=False))
    report = Report()
    check_build(config, report)
    assert findings(report, Level.FAIL) == []
    assert findings(report, Level.NOTE)


def test_the_build_check_runs_first(config: SiteConfig) -> None:
    # An engineer reading a doctor report on a freshly flashed box wants the
    # build line before anything else.
    report = run_checks(config)
    assert report.findings[0].check == "build"


def test_a_connection_left_open_does_not_stop_the_check(config: SiteConfig, tmp_path: Path) -> None:
    connection: sqlite3.Connection = open_archive(config.archive.file)
    try:
        report = Report()
        check_build(config, report)
        assert findings(report, Level.FAIL) == []
    finally:
        connection.close()
