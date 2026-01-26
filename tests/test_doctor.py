from __future__ import annotations

from pathlib import Path

from stanchion.archive.config import ArchiveSettings
from stanchion.config import IngestConfig, SiteConfig
from stanchion.doctor import PROBE_PAN, Level, Report, check_writable, run_checks
from stanchion.redact.config import RedactConfig
from stanchion.ship.config import ShipSettings
from stanchion.spool.config import SpoolSettings
from tests.support.logfiles import SiteTree, cri_full

TS = "2025-03-04T12:00:00.000000000Z"


def build(tree: SiteTree, tmp_path: Path, **over: object) -> SiteConfig:
    settings: dict[str, object] = {
        "site": "US-TX-0417",
        "ingest": IngestConfig(log_root=tree.root.as_posix()),
        "spool": SpoolSettings(directory=(tmp_path / "spool").as_posix()),
        "archive": ArchiveSettings(path=(tmp_path / "archive" / "records.sqlite3").as_posix()),
        "ship": ShipSettings(endpoint=f"file://{(tmp_path / 'outbox').as_posix()}"),
    }
    settings.update(over)
    return SiteConfig(**settings)  # type: ignore[arg-type]


def healthy(tree: SiteTree, tmp_path: Path, **over: object) -> SiteConfig:
    tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    return build(tree, tmp_path, **over)


def levels(report: Report, check: str) -> list[Level]:
    return [item.level for item in report.findings if item.check == check]


def test_a_good_site_passes(tree: SiteTree, tmp_path: Path) -> None:
    report = run_checks(healthy(tree, tmp_path))
    assert report.healthy is True
    assert report.failures == []


def test_check_writable_writes_and_cleans_up(tmp_path: Path) -> None:
    assert check_writable(tmp_path / "fresh") is None
    assert list((tmp_path / "fresh").iterdir()) == []


def test_check_writable_reports_a_file_in_the_way(tmp_path: Path) -> None:
    blocker = tmp_path / "spool"
    blocker.write_text("not a directory", encoding="utf-8")
    assert check_writable(blocker) is not None


def test_a_missing_log_root_is_a_failure(tmp_path: Path, tree: SiteTree) -> None:
    config = build(tree, tmp_path, ingest=IngestConfig(log_root="/definitely/not/here"))
    report = run_checks(config)
    assert Level.FAIL in levels(report, "log root")
    assert report.healthy is False


def test_an_empty_log_root_is_a_failure(tree: SiteTree, tmp_path: Path) -> None:
    tree.root.mkdir(parents=True, exist_ok=True)
    report = run_checks(build(tree, tmp_path))
    assert Level.FAIL in levels(report, "log root")


def test_a_followed_unit_is_named(tree: SiteTree, tmp_path: Path) -> None:
    report = run_checks(healthy(tree, tmp_path))
    assert any("pump-bridge" in item.message for item in report.findings)


def test_unrecognised_files_under_the_root_are_a_warning(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    (tree.root / "messages").write_text("not ours", encoding="utf-8")
    report = run_checks(build(tree, tmp_path))
    assert Level.WARN in levels(report, "log root")


def test_a_spool_that_cannot_be_written_is_a_failure(tree: SiteTree, tmp_path: Path) -> None:
    blocker = tmp_path / "spool"
    blocker.write_text("not a directory", encoding="utf-8")
    report = run_checks(healthy(tree, tmp_path))
    assert Level.FAIL in levels(report, "spool")


def test_the_redaction_check_actually_runs_the_pass(tree: SiteTree, tmp_path: Path) -> None:
    report = run_checks(healthy(tree, tmp_path))
    assert Level.NOTE in levels(report, "redaction")
    assert not any(PROBE_PAN in item.message for item in report.findings)


def test_redaction_turned_off_is_a_warning(tree: SiteTree, tmp_path: Path) -> None:
    config = healthy(tree, tmp_path, site="XX-LAB-0001", redact=RedactConfig(enabled=False))
    report = run_checks(config)
    assert Level.WARN in levels(report, "redaction")


def test_the_archive_check_opens_it(tree: SiteTree, tmp_path: Path) -> None:
    report = run_checks(healthy(tree, tmp_path))
    assert Level.NOTE in levels(report, "archive")
    assert (tmp_path / "archive" / "records.sqlite3").exists()


def test_an_unopenable_archive_is_a_failure(tree: SiteTree, tmp_path: Path) -> None:
    target = tmp_path / "archive" / "records.sqlite3"
    target.parent.mkdir(parents=True)
    target.write_bytes(b"not a database" * 20)
    report = run_checks(healthy(tree, tmp_path))
    assert Level.FAIL in levels(report, "archive")


def test_a_disabled_archive_is_only_a_note(tree: SiteTree, tmp_path: Path) -> None:
    config = healthy(tree, tmp_path, archive=ArchiveSettings(enabled=False))
    report = run_checks(config)
    assert levels(report, "archive") == [Level.NOTE]


def test_the_placeholder_endpoint_is_a_failure(tree: SiteTree, tmp_path: Path) -> None:
    config = healthy(tree, tmp_path, ship=ShipSettings())
    report = run_checks(config)
    assert Level.FAIL in levels(report, "ship")


def test_a_plain_http_endpoint_is_a_warning(tree: SiteTree, tmp_path: Path) -> None:
    config = healthy(tree, tmp_path, ship=ShipSettings(endpoint="http://10.0.0.9/v1"))
    report = run_checks(config)
    assert Level.WARN in levels(report, "ship")


def test_a_file_endpoint_is_checked_for_writability(tree: SiteTree, tmp_path: Path) -> None:
    report = run_checks(healthy(tree, tmp_path))
    assert Level.NOTE in levels(report, "ship")
    assert (tmp_path / "outbox").is_dir()


def test_the_state_directory_is_checked(tree: SiteTree, tmp_path: Path) -> None:
    report = run_checks(healthy(tree, tmp_path))
    assert Level.NOTE in levels(report, "state")


def test_the_report_counts_what_it_found(tree: SiteTree, tmp_path: Path) -> None:
    rendered = run_checks(healthy(tree, tmp_path)).render()
    assert "0 failures" in rendered
    assert "checks" in rendered


def test_a_finding_reads_as_one_line() -> None:
    report = Report()
    report.fail("spool", "cannot write")
    assert str(report.findings[0]) == "FAIL spool              cannot write"


def test_the_render_lists_every_finding() -> None:
    report = Report()
    report.fail("a", "one")
    report.warn("b", "two")
    report.note("c", "three")
    lines = report.render().splitlines()
    assert len(lines) == 4
    assert report.healthy is False


def test_warnings_alone_leave_the_site_healthy() -> None:
    report = Report()
    report.warn("a", "something to raise a ticket about")
    assert report.healthy is True
    assert len(report.warnings) == 1
