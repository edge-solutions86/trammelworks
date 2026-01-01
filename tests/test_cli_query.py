from __future__ import annotations

from pathlib import Path

import pytest
from click.testing import CliRunner, Result

from stanchion.archive.repository import Repository
from stanchion.archive.schema import open_archive
from stanchion.cli.main import EXIT_OK, cli
from stanchion.records import LogRecord, Stream
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-03-04T12:00:00Z")

CORPUS = [
    ("payment-kernel", "11:00:00", "auth approved", {"lane": "3"}),
    ("payment-kernel", "11:15:00", "auth declined", {"lane": "4"}),
    ("pump-bridge", "11:30:00", "nozzle 1 lifted", {}),
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
        "archive:\n"
        f"  path: {archive_file.as_posix()}\n",
        encoding="utf-8",
    )
    return target


@pytest.fixture
def filled(archive_file: Path) -> Repository:
    repo = Repository(connection=open_archive(archive_file))
    for unit, when, message, labels in CORPUS:
        repo.add(
            LogRecord(
                site="US-TX-0417",
                unit=unit,
                stream=Stream.STDOUT,
                observed_at=NanoTime.parse(f"2025-03-04T{when}Z"),
                message=message,
                labels=labels,
            ),
            NOW,
        )
    repo.connection.close()
    return repo


def invoke(config: Path, *args: str) -> Result:
    return CliRunner().invoke(cli, ["--config", str(config), "query", *args])


def test_an_empty_filter_lists_everything(config: Path, filled: Repository) -> None:
    result = invoke(config, "")
    assert result.exit_code == EXIT_OK
    assert len(result.output.strip().splitlines()) == 3


def test_a_record_line_carries_the_time_and_unit(config: Path, filled: Repository) -> None:
    result = invoke(config, "unit = pump-bridge")
    assert "pump-bridge stdout nozzle 1 lifted" in result.output
    assert "2025-03-04T11:30:00" in result.output


def test_a_filter_narrows(config: Path, filled: Repository) -> None:
    result = invoke(config, "message ~ declined")
    assert result.output.strip().count("\n") == 0
    assert "auth declined" in result.output


def test_a_label_filter_works_from_the_command_line(config: Path, filled: Repository) -> None:
    result = invoke(config, "label.lane = 3")
    assert "auth approved" in result.output
    assert "auth declined" not in result.output


def test_counting_prints_a_number(config: Path, filled: Repository) -> None:
    assert invoke(config, "--count", "unit = payment-kernel").output.strip() == "2"


def test_counting_an_empty_result_is_zero(config: Path, filled: Repository) -> None:
    assert invoke(config, "--count", "unit = absent").output.strip() == "0"


def test_a_limit_is_honoured(config: Path, filled: Repository) -> None:
    assert len(invoke(config, "limit 1").output.strip().splitlines()) == 1


def test_explain_shows_the_plan(config: Path, filled: Repository) -> None:
    result = invoke(config, "--explain", "unit = a")
    assert "sql: unit = ?" in result.output


def test_explain_names_a_post_filter(config: Path, filled: Repository) -> None:
    result = invoke(config, "--explain", "label.lane = 3")
    assert "in memory" in result.output


def test_a_long_message_is_truncated_by_default(config: Path, archive_file: Path) -> None:
    repo = Repository(connection=open_archive(archive_file))
    repo.add(
        LogRecord(
            site="US-TX-0417",
            unit="pump-bridge",
            stream=Stream.STDOUT,
            observed_at=NOW,
            message="z" * 300,
        ),
        NOW,
    )
    repo.connection.close()
    assert "z" * 200 not in invoke(config, "").output


def test_full_prints_the_whole_message(config: Path, archive_file: Path) -> None:
    repo = Repository(connection=open_archive(archive_file))
    repo.add(
        LogRecord(
            site="US-TX-0417",
            unit="pump-bridge",
            stream=Stream.STDOUT,
            observed_at=NOW,
            message="z" * 300,
        ),
        NOW,
    )
    repo.connection.close()
    assert "z" * 300 in invoke(config, "--full", "").output


def test_a_low_confidence_record_is_marked(config: Path, archive_file: Path) -> None:
    repo = Repository(connection=open_archive(archive_file))
    repo.add(
        LogRecord(
            site="US-TX-0417",
            unit="pump-bridge",
            stream=Stream.STDOUT,
            observed_at=NOW,
            message="uncertain",
            clock_confidence="extrapolated",
        ),
        NOW,
    )
    repo.connection.close()
    assert "Z? pump-bridge" in invoke(config, "").output


def test_the_ceiling_truncates_and_warns(config: Path, filled: Repository) -> None:
    result = invoke(config, "--ceiling", "1", "")
    assert "narrow the filter" in result.output


def test_a_broken_filter_is_refused(config: Path, filled: Repository) -> None:
    result = invoke(config, "unit ==== a")
    assert result.exit_code != EXIT_OK
    assert "stanchion.query" in result.output


def test_an_unknown_field_is_refused(config: Path, filled: Repository) -> None:
    result = invoke(config, "unti = a")
    assert "unknown field" in result.output


def test_a_missing_archive_is_reported(config: Path) -> None:
    result = invoke(config, "")
    assert result.exit_code != EXIT_OK
    assert "does not exist" in result.output


def test_a_site_with_no_archive_says_so(tmp_path: Path) -> None:
    target = tmp_path / "site.yaml"
    target.write_text("site: US-TX-0417\narchive:\n  enabled: false\n", encoding="utf-8")
    result = CliRunner().invoke(cli, ["--config", str(target), "query", ""])
    assert "keeps no archive" in result.output


def test_a_query_runs_while_the_collector_is_writing(
    config: Path, archive_file: Path, filled: Repository
) -> None:
    # The collector holds a write connection to the same file all day. A query
    # that took a write lock would stop it, so this holds one open and demands
    # an answer anyway.
    writer = open_archive(archive_file)
    writer.execute("BEGIN IMMEDIATE")
    try:
        result = invoke(config, "")
        assert result.exit_code == EXIT_OK
        assert len(result.output.strip().splitlines()) == 3
    finally:
        writer.execute("ROLLBACK")
        writer.close()


def test_verbose_reports_what_was_scanned(config: Path, filled: Repository) -> None:
    result = CliRunner().invoke(cli, ["--config", str(config), "-v", "query", ""])
    assert "records from" in result.output
