from __future__ import annotations

import csv
import io
import json
from pathlib import Path

import pytest
from click.testing import CliRunner, Result

from stanchion.archive.repository import Repository
from stanchion.archive.schema import open_archive
from stanchion.cli.main import EXIT_OK, cli
from stanchion.cli.report_cmd import EXIT_EMPTY
from stanchion.records import LogRecord, Stream
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-03-04T12:00:00Z")

CORPUS = [
    ("payment-kernel", "11:00:00", "auth approved"),
    ("payment-kernel", "11:15:00", "auth declined, lane 4"),
    ("pump-bridge", "11:30:00", "nozzle 1 lifted"),
]


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
        "  budget: 1MiB\n"
        "  segment: 16KiB\n"
        "archive:\n"
        f"  path: {archive_file.as_posix()}\n",
        encoding="utf-8",
    )
    return target


@pytest.fixture
def filled(archive_file: Path) -> None:
    repo = Repository(connection=open_archive(archive_file))
    for unit, when, message in CORPUS:
        repo.add(
            LogRecord(
                site="US-TX-0417",
                unit=unit,
                stream=Stream.STDOUT,
                observed_at=NanoTime.parse(f"2025-03-04T{when}Z"),
                message=message,
            ),
            NOW,
        )
    repo.connection.close()


def invoke(config: Path, *args: str) -> Result:
    return CliRunner().invoke(cli, ["--config", str(config), *args])


def test_a_report_prints_a_table(config: Path, filled: None) -> None:
    result = invoke(config, "report")
    assert result.exit_code == EXIT_OK
    assert "auth approved" in result.output
    assert result.output.splitlines()[0].startswith("time")


def test_a_report_can_be_filtered(config: Path, filled: None) -> None:
    result = invoke(config, "report", "unit = pump-bridge")
    assert "nozzle 1 lifted" in result.output
    assert "auth approved" not in result.output


def test_a_report_of_nothing_exits_four(config: Path, filled: None) -> None:
    assert invoke(config, "report", "unit = absent").exit_code == EXIT_EMPTY


def test_the_csv_format_is_readable_by_a_csv_reader(config: Path, filled: None) -> None:
    result = invoke(config, "report", "--format", "csv")
    rows = list(csv.reader(io.StringIO(result.output.strip())))
    assert rows[0][0] == "time"
    assert rows[2][5] == "auth declined, lane 4"


def test_the_jsonl_format_is_one_object_per_line(config: Path, filled: None) -> None:
    result = invoke(config, "report", "--format", "jsonl")
    lines = result.output.strip().splitlines()
    assert len(lines) == 3
    assert json.loads(lines[0])["unit"] == "payment-kernel"


def test_the_markdown_format_has_a_separator(config: Path, filled: None) -> None:
    result = invoke(config, "report", "--format", "markdown")
    assert result.output.splitlines()[1].startswith("| ---")


def test_an_unknown_format_is_refused(config: Path, filled: None) -> None:
    result = invoke(config, "report", "--format", "pdf")
    assert result.exit_code not in (EXIT_OK, EXIT_EMPTY)
    assert "unknown report format" in result.output


def test_the_header_can_be_dropped(config: Path, filled: None) -> None:
    result = invoke(config, "report", "--no-header")
    assert not result.output.splitlines()[0].startswith("time")


def test_a_report_can_be_written_to_a_file(config: Path, filled: None, tmp_path: Path) -> None:
    target = tmp_path / "case-41.csv"
    result = invoke(config, "report", "--format", "csv", "--out", str(target))
    assert target.exists()
    assert "auth approved" in target.read_text(encoding="utf-8")
    assert "wrote 3 records" in result.output


def test_a_written_report_ends_with_a_newline(config: Path, filled: None, tmp_path: Path) -> None:
    target = tmp_path / "case-41.txt"
    invoke(config, "report", "--out", str(target))
    assert target.read_text(encoding="utf-8").endswith("\n")


def test_the_message_width_is_honoured(config: Path, filled: None) -> None:
    result = invoke(config, "report", "--width", "8", "unit = pump-bridge")
    assert "nozzle 1 lifted" not in result.output
    assert "…" in result.output


def test_a_site_with_no_archive_says_so(tmp_path: Path) -> None:
    target = tmp_path / "site.yaml"
    target.write_text("site: US-TX-0417\narchive:\n  enabled: false\n", encoding="utf-8")
    assert "keeps no archive" in invoke(target, "report").output


def test_a_summary_names_the_site(config: Path, filled: None) -> None:
    result = invoke(config, "summary")
    assert result.exit_code == EXIT_OK
    assert result.output.startswith("US-TX-0417")


def test_a_summary_counts_the_archive(config: Path, filled: None) -> None:
    assert "3 records kept" in invoke(config, "summary").output


def test_a_summary_reports_the_spool(config: Path, filled: None) -> None:
    assert "spool   0B of 1MiB" in invoke(config, "summary").output


def test_a_summary_flags_a_site_following_nothing(config: Path, filled: None) -> None:
    result = invoke(config, "summary")
    assert "ATTENTION" in result.output
    assert "no container log files" in result.output


def test_the_json_summary_parses(config: Path, filled: None) -> None:
    payload = json.loads(invoke(config, "summary", "--json").output)
    assert payload["site"] == "US-TX-0417"
    assert payload["archived"] == 3


def test_a_summary_without_an_archive_still_runs(tmp_path: Path) -> None:
    target = tmp_path / "site.yaml"
    target.write_text("site: US-TX-0417\narchive:\n  enabled: false\n", encoding="utf-8")
    result = invoke(target, "summary")
    assert result.exit_code == EXIT_OK
    assert "0 records kept" in result.output


def test_a_missing_config_fails(tmp_path: Path) -> None:
    result = CliRunner().invoke(cli, ["--config", str(tmp_path / "nope.yaml"), "summary"])
    assert "stanchion.config" in result.output
