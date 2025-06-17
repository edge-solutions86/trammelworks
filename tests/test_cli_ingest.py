from __future__ import annotations

from pathlib import Path

import pytest
from click.testing import CliRunner

from stanchion.cli.main import EXIT_OK, cli
from tests.support.logfiles import SiteTree, cri_full, cri_line

TS = "2025-03-04T11:30:00.123456789Z"


@pytest.fixture
def config(tmp_path: Path, tree: SiteTree) -> Path:
    tree.root.mkdir(parents=True, exist_ok=True)
    target = tmp_path / "site.yaml"
    target.write_text(
        f"site: US-TX-0417\ningest:\n  log_root: {tree.root.as_posix()}\n", encoding="utf-8"
    )
    return target


@pytest.fixture
def runner() -> CliRunner:
    return CliRunner()


def invoke(runner: CliRunner, config: Path, *args: str) -> object:
    return runner.invoke(cli, ["--config", str(config), *args])


def test_discover_names_the_log_root(runner: CliRunner, config: Path, tree: SiteTree) -> None:
    result = invoke(runner, config, "discover")
    assert result.exit_code == EXIT_OK  # type: ignore[attr-defined]
    assert tree.root.as_posix() in result.output  # type: ignore[attr-defined]


def test_discover_lists_a_followed_unit(runner: CliRunner, config: Path, tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    result = invoke(runner, config, "discover")
    assert "follow  pump-bridge" in result.output  # type: ignore[attr-defined]
    assert "cri" in result.output  # type: ignore[attr-defined]


def test_discover_says_so_when_there_is_nothing(runner: CliRunner, config: Path) -> None:
    result = invoke(runner, config, "discover")
    assert "follow  nothing" in result.output  # type: ignore[attr-defined]


def test_discover_reports_skips_with_a_reason(
    runner: CliRunner, config: Path, tree: SiteTree
) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    (tree.root / "messages").write_text("not ours", encoding="utf-8")
    result = invoke(runner, config, "discover")
    assert "skipped unparseable" in result.output  # type: ignore[attr-defined]


def test_discover_can_hide_the_skips(runner: CliRunner, config: Path, tree: SiteTree) -> None:
    (tree.root / "messages").write_text("not ours", encoding="utf-8")
    result = invoke(runner, config, "discover", "--no-skipped")
    assert "skipped" not in result.output  # type: ignore[attr-defined]


def test_tail_prints_a_record(runner: CliRunner, config: Path, tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "nozzle 1 lifted")])
    result = invoke(runner, config, "tail")
    assert "nozzle 1 lifted" in result.output  # type: ignore[attr-defined]
    assert "pump-bridge stdout" in result.output  # type: ignore[attr-defined]


def test_tail_prints_the_timestamp_in_full(runner: CliRunner, config: Path, tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "x")])
    result = invoke(runner, config, "tail")
    assert TS in result.output  # type: ignore[attr-defined]


def test_tail_joins_split_chunks(runner: CliRunner, config: Path, tree: SiteTree) -> None:
    tree.write_cri(
        "pump-bridge",
        [cri_line(TS, "stdout", "P", "first "), cri_line(TS, "stdout", "F", "second")],
    )
    result = invoke(runner, config, "tail")
    assert "first second" in result.output  # type: ignore[attr-defined]


def test_tail_escapes_a_newline_so_one_record_is_one_line(
    runner: CliRunner, config: Path, tree: SiteTree
) -> None:
    long_message = "x" * 300
    tree.write_cri("pump-bridge", [cri_full(TS, long_message)])
    result = invoke(runner, config, "tail", "--full")
    assert len(result.output.strip().splitlines()) == 1  # type: ignore[attr-defined]


def test_tail_truncates_by_default(runner: CliRunner, config: Path, tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "y" * 300)])
    result = invoke(runner, config, "tail")
    assert "y" * 160 in result.output  # type: ignore[attr-defined]
    assert "y" * 200 not in result.output  # type: ignore[attr-defined]


def test_tail_full_prints_everything(runner: CliRunner, config: Path, tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "z" * 300)])
    result = invoke(runner, config, "tail", "--full")
    assert "z" * 300 in result.output  # type: ignore[attr-defined]


def test_tail_can_filter_to_one_unit(runner: CliRunner, config: Path, tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "pump line")])
    tree.write_cri("tank-gauge", [cri_full(TS, "gauge line")])
    result = invoke(runner, config, "tail", "--unit", "pump-bridge")
    assert "pump line" in result.output  # type: ignore[attr-defined]
    assert "gauge line" not in result.output  # type: ignore[attr-defined]


def test_tail_flushes_an_unfinished_line_at_the_end(
    runner: CliRunner, config: Path, tree: SiteTree
) -> None:
    tree.write_cri("pump-bridge", [cri_line(TS, "stdout", "P", "never closed")])
    result = invoke(runner, config, "tail")
    assert "never closed" in result.output  # type: ignore[attr-defined]


def test_tail_refuses_zero_passes(runner: CliRunner, config: Path) -> None:
    result = invoke(runner, config, "tail", "--passes", "0")
    assert result.exit_code != EXIT_OK  # type: ignore[attr-defined]
    assert "at least 1" in result.output  # type: ignore[attr-defined]


def test_tail_with_several_passes_still_reads_once(
    runner: CliRunner, config: Path, tree: SiteTree
) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "only once")])
    result = invoke(runner, config, "tail", "--passes", "3")
    assert result.output.count("only once") == 1  # type: ignore[attr-defined]


def test_verbose_tail_reports_a_count_on_stderr(
    runner: CliRunner, config: Path, tree: SiteTree
) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    result = runner.invoke(cli, ["--config", str(config), "-v", "tail"])
    assert "1 records" in result.output


def test_discover_on_a_missing_config_fails(runner: CliRunner, tmp_path: Path) -> None:
    result = runner.invoke(cli, ["--config", str(tmp_path / "nope.yaml"), "discover"])
    assert result.exit_code != EXIT_OK
    assert "stanchion.config" in result.output
