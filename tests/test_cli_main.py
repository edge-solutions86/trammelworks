from __future__ import annotations

import json
import sqlite3
import sys
from pathlib import Path

import pytest
from click.testing import CliRunner

from stanchion.archive.schema import SCHEMA_VERSION, open_archive
from stanchion.cli.main import (
    EXIT_CONFIG,
    EXIT_FAILED,
    EXIT_OK,
    Context,
    cli,
    fail,
    loaded,
    reading_archive,
    reporting,
    run,
)
from stanchion.errors import ConfigError, QueryError, ShipError
from stanchion.version import SPOOL_FORMAT, VERSION, Build

SITE_YAML = """
site: US-TX-0417
spool:
  budget: 1GiB
  segment: 16MiB
"""


@pytest.fixture
def config(tmp_path: Path) -> Path:
    target = tmp_path / "site.yaml"
    target.write_text(SITE_YAML, encoding="utf-8")
    return target


@pytest.fixture
def runner() -> CliRunner:
    return CliRunner()


def test_help_lists_the_commands(runner: CliRunner) -> None:
    result = runner.invoke(cli, ["--help"])
    assert result.exit_code == EXIT_OK
    assert "config" in result.output
    assert "version" in result.output


def test_short_help_flag_works(runner: CliRunner) -> None:
    assert runner.invoke(cli, ["-h"]).exit_code == EXIT_OK


def test_version_command_prints_the_version(runner: CliRunner) -> None:
    result = runner.invoke(cli, ["version"])
    assert result.output.strip() == VERSION


def test_version_option_prints_the_program_name(runner: CliRunner) -> None:
    result = runner.invoke(cli, ["--version"])
    assert "stanchion" in result.output
    assert VERSION in result.output


def test_config_prints_what_it_understood(runner: CliRunner, config: Path) -> None:
    result = runner.invoke(cli, ["--config", str(config), "config"])
    assert result.exit_code == EXIT_OK
    assert "site: US-TX-0417" in result.output
    assert "budget: 1073741824" in result.output


def test_config_check_is_quiet(runner: CliRunner, config: Path) -> None:
    result = runner.invoke(cli, ["--config", str(config), "config", "--check"])
    assert result.output.strip() == "ok US-TX-0417"


def test_a_missing_config_exits_two(runner: CliRunner, tmp_path: Path) -> None:
    result = runner.invoke(cli, ["--config", str(tmp_path / "nope.yaml"), "config"])
    assert result.exit_code == EXIT_CONFIG
    assert "stanchion.config" in result.output


def test_a_broken_config_names_the_code(runner: CliRunner, tmp_path: Path) -> None:
    target = tmp_path / "site.yaml"
    target.write_text("site: [oops", encoding="utf-8")
    result = runner.invoke(cli, ["--config", str(target), "config"])
    assert result.exit_code == EXIT_CONFIG
    assert "not valid yaml" in result.output


def test_a_config_failure_prints_no_traceback(runner: CliRunner, tmp_path: Path) -> None:
    result = runner.invoke(cli, ["--config", str(tmp_path / "nope.yaml"), "config"])
    assert "Traceback" not in result.output


def test_an_unknown_command_is_refused(runner: CliRunner) -> None:
    assert runner.invoke(cli, ["polish-the-pumps"]).exit_code != EXIT_OK


def test_the_default_config_path_is_the_etc_one(runner: CliRunner) -> None:
    result = runner.invoke(cli, ["--help"])
    assert "/etc/stanchion/site.yaml" in result.output


def test_run_returns_zero_for_a_good_command() -> None:
    assert run(["version"]) == EXIT_OK


def test_run_returns_two_for_a_missing_config(tmp_path: Path) -> None:
    assert run(["--config", str(tmp_path / "nope.yaml"), "config"]) == EXIT_CONFIG


def test_run_returns_a_code_for_a_bad_option() -> None:
    assert run(["--nonsense"]) != EXIT_OK


def test_verbose_is_accepted_on_the_group(runner: CliRunner, config: Path) -> None:
    result = runner.invoke(cli, ["--config", str(config), "-v", "config", "--check"])
    assert result.exit_code == EXIT_OK


def test_a_guard_lets_a_clean_run_through() -> None:
    seen = []
    with reporting():
        seen.append("ran")
    assert seen == ["ran"]


def test_a_guard_turns_an_error_into_one_line(capsys: pytest.CaptureFixture[str]) -> None:
    with pytest.raises(SystemExit) as caught:
        with reporting():
            raise ConfigError("no such file", detail="/etc/stanchion/site.yaml")
    assert caught.value.code == EXIT_FAILED
    assert "stanchion.config: no such file" in capsys.readouterr().err


def test_a_guard_carries_the_exit_code_it_was_given() -> None:
    with pytest.raises(SystemExit) as caught:
        with reporting(EXIT_CONFIG):
            raise ConfigError("bad")
    assert caught.value.code == EXIT_CONFIG


def test_a_guard_does_not_swallow_anything_else() -> None:
    with pytest.raises(ValueError):
        with reporting():
            raise ValueError("not ours")


def test_every_message_carries_the_error_code(capsys: pytest.CaptureFixture[str]) -> None:
    # The whole reason for one guard: a support call says paste me what it
    # says, and what it says has to name the code whichever command ran.
    for error in (ConfigError("a"), QueryError("b"), ShipError("c")):
        with pytest.raises(SystemExit), reporting():
            raise error
    printed = capsys.readouterr().err.splitlines()
    assert [line.split(":")[0] for line in printed] == [
        "stanchion.config",
        "stanchion.query",
        "stanchion.ship",
    ]


def test_loading_a_good_config_gives_it_back(config: Path) -> None:
    assert loaded(Context(config_path=config, verbose=False)).site == "US-TX-0417"


def test_loading_a_missing_config_stops(tmp_path: Path) -> None:
    with pytest.raises(SystemExit) as caught:
        loaded(Context(config_path=tmp_path / "absent.yaml", verbose=False))
    assert caught.value.code == EXIT_CONFIG


def test_failing_never_returns(capsys: pytest.CaptureFixture[str]) -> None:
    with pytest.raises(SystemExit) as caught:
        fail("gone wrong")
    assert caught.value.code == EXIT_FAILED
    assert capsys.readouterr().err.strip() == "gone wrong"


def archive_config(tmp_path: Path, enabled: bool = True) -> Path:
    target = tmp_path / "site.yaml"
    body = f"site: US-TX-0417\nspool:\n  directory: {(tmp_path / 'spool').as_posix()}\narchive:\n"
    if enabled:
        body += f"  path: {(tmp_path / 'archive' / 'records.sqlite3').as_posix()}\n"
    else:
        body += "  enabled: false\n"
    target.write_text(body, encoding="utf-8")
    return target


def made(tmp_path: Path) -> Path:
    """A config whose archive file has actually been created.

    Opening read only refuses a file that is not there, which is the right
    answer: a reader that created an empty archive would report a site with no
    records rather than a site that has never written one.
    """
    target = archive_config(tmp_path)
    open_archive(tmp_path / "archive" / "records.sqlite3").close()
    return target


def test_reading_a_missing_archive_is_refused(tmp_path: Path) -> None:
    obj = Context(config_path=archive_config(tmp_path), verbose=False)
    with pytest.raises(SystemExit):
        reading_archive(obj, "query")


def test_reading_an_archive_gives_back_the_config_and_a_connection(tmp_path: Path) -> None:
    obj = Context(config_path=made(tmp_path), verbose=False)
    config, connection = reading_archive(obj, "query")
    assert config.site == "US-TX-0417"
    assert connection.execute("SELECT COUNT(*) FROM records").fetchone()[0] == 0


def test_a_site_with_no_archive_stops_with_the_verb_it_was_given(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    obj = Context(config_path=archive_config(tmp_path, enabled=False), verbose=False)
    with pytest.raises(SystemExit):
        reading_archive(obj, "count")
    assert "nothing to count" in capsys.readouterr().err


def test_the_connection_it_hands_back_is_read_only(tmp_path: Path) -> None:
    # The collector is writing to this file while a report runs against it, so
    # a writable connection here would take the lock that stops the site.
    obj = Context(config_path=made(tmp_path), verbose=False)
    _, connection = reading_archive(obj, "query")
    with pytest.raises(sqlite3.OperationalError):
        connection.execute("DELETE FROM records")


def test_a_missing_config_stops_before_the_archive(tmp_path: Path) -> None:
    obj = Context(config_path=tmp_path / "absent.yaml", verbose=False)
    with pytest.raises(SystemExit) as caught:
        reading_archive(obj, "query")
    assert caught.value.code == EXIT_CONFIG


def test_the_version_command_prints_the_version_alone() -> None:
    result = CliRunner().invoke(cli, ["version"])
    assert result.output.strip() == VERSION


def test_the_full_version_names_every_moving_number() -> None:
    output = CliRunner().invoke(cli, ["version", "--full"]).output
    for word in ("stanchion", "spool format", "archive", "python", "platform"):
        assert word in output


def test_the_full_version_is_one_fact_per_line() -> None:
    lines = CliRunner().invoke(cli, ["version", "--full"]).output.strip().splitlines()
    assert len(lines) == 5


def test_the_version_can_come_back_as_json() -> None:
    payload = json.loads(CliRunner().invoke(cli, ["version", "--json"]).output)
    assert payload["version"] == VERSION
    assert payload["spool_format"] == SPOOL_FORMAT
    assert payload["archive_schema"] == SCHEMA_VERSION


def test_the_build_reports_the_running_python() -> None:
    built = Build.current()
    assert built.python.startswith(f"{sys.version_info.major}.{sys.version_info.minor}")
    assert built.platform == sys.platform


def test_a_build_with_nothing_filled_in_still_renders() -> None:
    assert "stanchion" in str(Build())
