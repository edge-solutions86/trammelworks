from __future__ import annotations

from pathlib import Path

import pytest
from click.testing import CliRunner

from stanchion.cli.main import EXIT_CONFIG, EXIT_OK, cli, run
from stanchion.version import VERSION

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
    assert "budget_bytes: 1073741824" in result.output


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
