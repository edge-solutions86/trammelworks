from __future__ import annotations

from pathlib import Path

import pytest
from click.testing import CliRunner, Result

from stanchion.archive.repository import Repository
from stanchion.archive.schema import open_archive
from stanchion.cli.main import EXIT_OK, cli
from stanchion.records import LogRecord, Stream
from stanchion.spool.priority import PriorityTable
from stanchion.timestamps import NanoTime

TABLE = PriorityTable(assignments={"price-sign": "chatter"})
NOW = NanoTime.parse("2025-09-01T12:00:00Z")


@pytest.fixture
def archive_file(tmp_path: Path) -> Path:
    return tmp_path / "archive" / "records.sqlite3"


@pytest.fixture
def config(tmp_path: Path, archive_file: Path) -> Path:
    target = tmp_path / "site.yaml"
    target.write_text(
        "site: US-TX-0417\n"
        "spool:\n"
        f"  directory: {(tmp_path / 'spool').as_posix()}\n"
        "archive:\n"
        f"  path: {archive_file.as_posix()}\n"
        "  retention:\n"
        "    chatter: 7d\n",
        encoding="utf-8",
    )
    return target


def seed(archive_file: Path, when: str) -> None:
    repo = Repository(connection=open_archive(archive_file), table=TABLE)
    repo.add(
        LogRecord(
            site="US-TX-0417",
            unit="price-sign",
            stream=Stream.STDOUT,
            observed_at=NanoTime.parse(when),
            message=f"heartbeat at {when}",
        ),
        NOW,
    )
    repo.connection.close()


def recent() -> str:
    """A timestamp inside every retention window, measured from the real clock.

    The sweep commands read the system clock, so a fixed date in the past would
    make this test depend on when it was run.
    """
    from stanchion.clocks import SystemClock

    return SystemClock().now().shifted(-3600 * 1_000_000_000).isoformat()


def invoke(config: Path, *args: str) -> Result:
    return CliRunner().invoke(cli, ["--config", str(config), *args])


def test_listing_an_empty_archive_says_no_holds(config: Path, archive_file: Path) -> None:
    seed(archive_file, "2025-09-01T00:00:00Z")
    assert "no holds" in invoke(config, "hold", "list").output


def test_a_hold_can_be_placed_and_listed(config: Path, archive_file: Path) -> None:
    seed(archive_file, "2025-09-01T00:00:00Z")
    assert invoke(config, "hold", "place", "case-41", "chargeback 8821").exit_code == EXIT_OK
    output = invoke(config, "hold", "list").output
    assert "case-41" in output
    assert "chargeback 8821" in output


def test_a_hold_with_no_window_covers_everything(config: Path, archive_file: Path) -> None:
    seed(archive_file, "2025-09-01T00:00:00Z")
    invoke(config, "hold", "place", "case-41", "dispute")
    assert "everything" in invoke(config, "hold", "list").output


def test_a_windowed_hold_shows_its_window(config: Path, archive_file: Path) -> None:
    seed(archive_file, "2025-09-01T00:00:00Z")
    invoke(
        config,
        "hold",
        "place",
        "case-41",
        "dispute",
        "--from",
        "2025-07-01T00:00:00Z",
        "--until",
        "2025-08-01T00:00:00Z",
    )
    output = invoke(config, "hold", "list").output
    assert "2025-07-01T00:00:00" in output
    assert "2025-08-01T00:00:00" in output


def test_a_hold_can_be_lifted(config: Path, archive_file: Path) -> None:
    seed(archive_file, "2025-09-01T00:00:00Z")
    invoke(config, "hold", "place", "case-41", "dispute")
    assert "lifted case-41" in invoke(config, "hold", "lift", "case-41").output
    assert "no holds" in invoke(config, "hold", "list").output


def test_lifting_an_absent_hold_is_refused(config: Path, archive_file: Path) -> None:
    seed(archive_file, "2025-09-01T00:00:00Z")
    result = invoke(config, "hold", "lift", "case-41")
    assert result.exit_code != EXIT_OK
    assert "no hold called case-41" in result.output


def test_a_hold_with_a_backwards_window_is_refused(config: Path, archive_file: Path) -> None:
    seed(archive_file, "2025-09-01T00:00:00Z")
    result = invoke(
        config,
        "hold",
        "place",
        "case-41",
        "dispute",
        "--from",
        "2025-08-01T00:00:00Z",
        "--until",
        "2025-07-01T00:00:00Z",
    )
    assert result.exit_code != EXIT_OK
    assert "cannot end before it starts" in result.output


def test_a_sweep_reports_the_policy(config: Path, archive_file: Path) -> None:
    seed(archive_file, "2025-09-01T00:00:00Z")
    assert "retention" in invoke(config, "sweep").output


def test_a_sweep_deletes_an_expired_record(config: Path, archive_file: Path) -> None:
    seed(archive_file, "2025-01-01T00:00:00Z")
    result = invoke(config, "sweep")
    assert "deleted 1" in result.output
    assert "chatter" in result.output


def test_a_sweep_leaves_a_fresh_record(config: Path, archive_file: Path) -> None:
    seed(archive_file, recent())
    assert "deleted 0" in invoke(config, "sweep").output


def test_a_dry_run_ignores_a_fresh_record(config: Path, archive_file: Path) -> None:
    seed(archive_file, recent())
    assert "0 records are old enough" in invoke(config, "sweep", "--dry-run").output


def test_a_dry_run_deletes_nothing(config: Path, archive_file: Path) -> None:
    seed(archive_file, "2025-01-01T00:00:00Z")
    result = invoke(config, "sweep", "--dry-run")
    assert "1 records are old enough" in result.output
    repo = Repository(connection=open_archive(archive_file, read_only=True))
    assert repo.count() == 1


def test_a_dry_run_names_the_window_per_class(config: Path, archive_file: Path) -> None:
    seed(archive_file, "2025-01-01T00:00:00Z")
    assert "chatter      kept for 7d" in invoke(config, "sweep", "--dry-run").output


def test_a_held_record_survives_a_sweep(config: Path, archive_file: Path) -> None:
    seed(archive_file, "2025-01-01T00:00:00Z")
    invoke(config, "hold", "place", "case-41", "dispute")
    invoke(config, "sweep")
    repo = Repository(connection=open_archive(archive_file, read_only=True))
    assert repo.count() == 1


def test_lifting_the_hold_then_sweeping_removes_it(config: Path, archive_file: Path) -> None:
    seed(archive_file, "2025-01-01T00:00:00Z")
    invoke(config, "hold", "place", "case-41", "dispute")
    invoke(config, "sweep")
    invoke(config, "hold", "lift", "case-41")
    invoke(config, "sweep")
    repo = Repository(connection=open_archive(archive_file, read_only=True))
    assert repo.count() == 0


def test_a_site_with_no_archive_cannot_hold(tmp_path: Path) -> None:
    target = tmp_path / "site.yaml"
    target.write_text("site: US-TX-0417\narchive:\n  enabled: false\n", encoding="utf-8")
    result = invoke(target, "hold", "list")
    assert "keeps no archive" in result.output


def test_a_site_with_no_archive_cannot_sweep(tmp_path: Path) -> None:
    target = tmp_path / "site.yaml"
    target.write_text("site: US-TX-0417\narchive:\n  enabled: false\n", encoding="utf-8")
    assert "keeps no archive" in invoke(target, "sweep").output
