from __future__ import annotations

import json
from pathlib import Path

import pytest
from click.testing import CliRunner, Result

from stanchion.cli.doctor_cmd import EXIT_BROKEN, EXIT_HEALTHY, EXIT_WARNED
from stanchion.cli.main import cli
from tests.support.logfiles import SiteTree, cri_full

TS = "2025-03-04T12:00:00.000000000Z"


def write_config(tmp_path: Path, tree: SiteTree, endpoint: str) -> Path:
    target = tmp_path / "site.yaml"
    target.write_text(
        "site: US-TX-0417\n"
        "ingest:\n"
        f"  log_root: {tree.root.as_posix()}\n"
        "spool:\n"
        f"  directory: {(tmp_path / 'spool').as_posix()}\n"
        "archive:\n"
        f"  path: {(tmp_path / 'archive' / 'records.sqlite3').as_posix()}\n"
        "ship:\n"
        f"  endpoint: {endpoint}\n",
        encoding="utf-8",
    )
    return target


@pytest.fixture
def good(tmp_path: Path, tree: SiteTree) -> Path:
    tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    return write_config(tmp_path, tree, f"file://{(tmp_path / 'outbox').as_posix()}")


@pytest.fixture
def warned(tmp_path: Path, tree: SiteTree) -> Path:
    tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    return write_config(tmp_path, tree, "http://10.0.0.9/v1")


@pytest.fixture
def broken(tmp_path: Path, tree: SiteTree) -> Path:
    tree.root.mkdir(parents=True, exist_ok=True)
    return write_config(tmp_path, tree, f"file://{(tmp_path / 'outbox').as_posix()}")


def invoke(config: Path, *args: str) -> Result:
    return CliRunner().invoke(cli, ["--config", str(config), "doctor", *args])


def test_a_good_site_exits_zero(good: Path) -> None:
    result = invoke(good)
    assert result.exit_code == EXIT_HEALTHY
    assert "0 failures, 0 warnings" in result.output


def test_the_site_is_named(good: Path) -> None:
    assert "checking US-TX-0417" in invoke(good).output


def test_a_warned_site_exits_two(warned: Path) -> None:
    result = invoke(warned)
    assert result.exit_code == EXIT_WARNED
    assert "plain http" in result.output


def test_strict_turns_a_warning_into_a_failure(warned: Path) -> None:
    assert invoke(warned, "--strict").exit_code == EXIT_BROKEN


def test_a_broken_site_exits_one(broken: Path) -> None:
    result = invoke(broken)
    assert result.exit_code == EXIT_BROKEN
    assert "FAIL" in result.output


def test_quiet_hides_the_notes(good: Path) -> None:
    result = invoke(good, "--quiet")
    assert "NOTE" not in result.output
    assert "0 failures" in result.output


def test_the_notes_are_shown_by_default(good: Path) -> None:
    assert "NOTE" in invoke(good).output


def test_the_json_form_parses(good: Path) -> None:
    payload = json.loads(invoke(good, "--json").output)
    assert payload["site"] == "US-TX-0417"
    assert payload["healthy"] is True
    assert payload["findings"]


def test_the_json_form_carries_every_finding(broken: Path) -> None:
    payload = json.loads(invoke(broken, "--json").output)
    assert payload["healthy"] is False
    assert any(item["level"] == "fail" for item in payload["findings"])


def test_a_missing_config_exits_one(tmp_path: Path) -> None:
    result = CliRunner().invoke(cli, ["--config", str(tmp_path / "nope.yaml"), "doctor"])
    assert result.exit_code == EXIT_BROKEN
    assert "stanchion.config" in result.output


def test_the_check_creates_what_it_probes(good: Path, tmp_path: Path) -> None:
    invoke(good)
    assert (tmp_path / "spool").is_dir()
    assert (tmp_path / "outbox").is_dir()
    assert (tmp_path / "archive" / "records.sqlite3").exists()


def test_running_twice_gives_the_same_answer(good: Path) -> None:
    first = invoke(good)
    second = invoke(good)
    assert first.exit_code == second.exit_code
    assert first.output.count("FAIL") == second.output.count("FAIL")
