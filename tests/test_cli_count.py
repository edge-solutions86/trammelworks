from __future__ import annotations

from pathlib import Path

import pytest
from click.testing import CliRunner, Result

from stanchion.archive.repository import Repository
from stanchion.archive.schema import open_archive
from stanchion.cli.count_cmd import bar
from stanchion.cli.main import EXIT_OK, cli
from stanchion.query.aggregate import Group, Tally
from stanchion.records import LogRecord, Stream
from stanchion.spool.priority import PriorityTable
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-06-10T12:00:00Z")
HOUR_NS = 60 * 60 * 1_000_000_000
TABLE = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})


@pytest.fixture
def archive_path(tmp_path: Path) -> Path:
    return tmp_path / "archive" / "records.sqlite3"


@pytest.fixture
def config(tmp_path: Path, archive_path: Path) -> Path:
    target = tmp_path / "site.yaml"
    target.write_text(
        "site: US-TX-0417\n"
        "ingest:\n"
        f"  log_root: {(tmp_path / 'containers').as_posix()}\n"
        "spool:\n"
        f"  directory: {(tmp_path / 'spool').as_posix()}\n"
        "archive:\n"
        f"  path: {archive_path.as_posix()}\n",
        encoding="utf-8",
    )
    return target


@pytest.fixture
def repo(archive_path: Path) -> Repository:
    return Repository(connection=open_archive(archive_path), table=TABLE)


def add(
    repo: Repository, unit: str, hours_ago: int = 0, labels: dict[str, str] | None = None
) -> None:
    repo.add(
        LogRecord(
            site="US-TX-0417",
            unit=unit,
            stream=Stream.STDOUT,
            observed_at=NanoTime(NOW.epoch_ns - (hours_ago * HOUR_NS)),
            message=f"{unit} at {hours_ago}",
            labels=dict(labels or {}),
        ),
        NOW,
    )


def fill(repo: Repository) -> None:
    for index in range(6):
        add(repo, "pump-bridge", hours_ago=index)
    for index in range(2):
        add(repo, "price-sign", hours_ago=index, labels={"lane": "2"})


def invoke(config: Path, *args: str) -> Result:
    return CliRunner().invoke(cli, ["--config", str(config), "count", *args])


def test_a_bar_is_empty_for_nothing() -> None:
    assert bar(0, 10) == ""


def test_a_bar_fills_the_column_for_the_biggest() -> None:
    assert len(bar(10, 10)) == 32


def test_a_small_group_still_gets_a_mark() -> None:
    assert bar(1, 10_000) == "#"


def test_a_bar_is_in_proportion() -> None:
    assert len(bar(5, 10)) == 16


def test_a_bar_against_nothing_is_empty() -> None:
    assert bar(3, 0) == ""


def test_a_bar_never_runs_past_its_width() -> None:
    assert len(bar(50, 10)) == 32


def test_counting_groups_by_unit_by_default(config: Path, repo: Repository) -> None:
    fill(repo)
    result = invoke(config)
    assert result.exit_code == EXIT_OK
    assert "pump-bridge" in result.output
    assert "price-sign" in result.output


def test_the_counts_are_printed(config: Path, repo: Repository) -> None:
    fill(repo)
    lines = invoke(config).output.splitlines()
    assert lines[0].split()[1] == "6"


def test_the_total_is_printed(config: Path, repo: Repository) -> None:
    fill(repo)
    assert "8 records in 2 groups" in invoke(config).output


def test_grouping_by_class(config: Path, repo: Repository) -> None:
    fill(repo)
    result = invoke(config, "--by", "class")
    assert "standard" in result.output
    assert "chatter" in result.output


def test_an_unknown_group_is_refused(config: Path, repo: Repository) -> None:
    fill(repo)
    result = invoke(config, "--by", "message")
    assert result.exit_code != EXIT_OK
    assert "cannot group by" in result.output


def test_a_filter_narrows_the_count(config: Path, repo: Repository) -> None:
    fill(repo)
    result = invoke(config, "unit = pump-bridge")
    assert "price-sign" not in result.output
    assert "6 records in 1 groups" in result.output


def test_a_label_filter_works_too(config: Path, repo: Repository) -> None:
    fill(repo)
    assert "2 records in 1 groups" in invoke(config, "label.lane = 2").output


def test_a_filter_that_matches_nothing_says_so(config: Path, repo: Repository) -> None:
    fill(repo)
    assert "nothing matched" in invoke(config, "unit = absent").output


def test_a_bad_filter_is_refused(config: Path, repo: Repository) -> None:
    fill(repo)
    result = invoke(config, "unit ==== 4")
    assert result.exit_code != EXIT_OK


def test_counting_over_time(config: Path, repo: Repository) -> None:
    fill(repo)
    result = invoke(config, "--every", "1h")
    assert result.exit_code == EXIT_OK
    assert "2025-06-10T" in result.output


def test_time_buckets_come_back_in_time_order(config: Path, repo: Repository) -> None:
    fill(repo)
    lines = [line for line in invoke(config, "--every", "1h").output.splitlines() if "T" in line]
    assert lines == sorted(lines)


def test_a_wider_bucket_gathers_more(config: Path, repo: Repository) -> None:
    fill(repo)
    narrow = invoke(config, "--every", "1h").output
    wide = invoke(config, "--every", "24h").output
    assert "in 1 groups" in wide
    assert "in 1 groups" not in narrow


def test_a_bad_bucket_is_refused(config: Path, repo: Repository) -> None:
    fill(repo)
    assert invoke(config, "--every", "a while").exit_code != EXIT_OK


def test_both_groupings_at_once_are_refused(config: Path, repo: Repository) -> None:
    fill(repo)
    result = invoke(config, "--by", "unit", "--every", "1h")
    assert "not both at once" in result.output


def test_a_site_with_no_archive_says_so(tmp_path: Path) -> None:
    target = tmp_path / "site.yaml"
    target.write_text(
        "site: US-TX-0417\n"
        "ingest:\n"
        f"  log_root: {(tmp_path / 'containers').as_posix()}\n"
        "spool:\n"
        f"  directory: {(tmp_path / 'spool').as_posix()}\n"
        "archive:\n"
        "  enabled: false\n",
        encoding="utf-8",
    )
    assert "keeps no archive" in invoke(target).output


def test_a_missing_config_is_refused(tmp_path: Path) -> None:
    result = CliRunner().invoke(cli, ["--config", str(tmp_path / "nope.yaml"), "count"])
    assert result.exit_code != EXIT_OK


def test_the_verbose_flag_says_where_it_counted(config: Path, repo: Repository) -> None:
    fill(repo)
    result = CliRunner().invoke(
        cli, ["--config", str(config), "--verbose", "count"], catch_exceptions=False
    )
    assert "counted in the archive" in result.output


def test_a_tally_with_no_groups_renders_nothing() -> None:
    from stanchion.cli.count_cmd import render

    assert render(Tally(), as_time=False) == []


def test_a_group_line_carries_the_key_and_the_count() -> None:
    from stanchion.cli.count_cmd import render

    line = render(Tally(groups=[Group("pump-bridge", 4)]), as_time=False)[0]
    assert line.startswith("pump-bridge")
    assert "4" in line
