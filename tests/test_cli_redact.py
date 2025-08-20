from __future__ import annotations

from pathlib import Path

import pytest
from click.testing import CliRunner, Result

from stanchion.cli.main import cli
from stanchion.cli.redact_cmd import EXIT_CLEAN, EXIT_FOUND

VISA = "4111111111111111"
BARE2 = "4111111111111111=270310112345"

SITE_YAML = """
site: US-TX-0417
redact:
  rules:
    driver_id:
      pattern: driver=(\\d{4,10})
"""


@pytest.fixture
def config(tmp_path: Path) -> Path:
    target = tmp_path / "site.yaml"
    target.write_text(SITE_YAML, encoding="utf-8")
    return target


def sample(tmp_path: Path, *lines: str) -> Path:
    target = tmp_path / "sample.log"
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return target


def invoke(config: Path, *args: str, stdin: str | None = None) -> Result:
    return CliRunner().invoke(cli, ["--config", str(config), "redact", *args], input=stdin)


def test_a_clean_file_exits_zero(config: Path, tmp_path: Path) -> None:
    result = invoke(config, str(sample(tmp_path, "nozzle 1 lifted")))
    assert result.exit_code == EXIT_CLEAN


def test_a_clean_file_is_printed_back(config: Path, tmp_path: Path) -> None:
    result = invoke(config, str(sample(tmp_path, "nozzle 1 lifted")))
    assert "nozzle 1 lifted" in result.output


def test_a_file_with_a_pan_exits_three(config: Path, tmp_path: Path) -> None:
    result = invoke(config, str(sample(tmp_path, f"pan={VISA}")))
    assert result.exit_code == EXIT_FOUND


def test_the_pan_is_masked_in_the_output(config: Path, tmp_path: Path) -> None:
    result = invoke(config, str(sample(tmp_path, f"pan={VISA}")))
    assert "411111******1111" in result.output
    assert VISA not in result.output


def test_a_stripe_is_removed(config: Path, tmp_path: Path) -> None:
    result = invoke(config, str(sample(tmp_path, f"swipe {BARE2}")))
    assert "[redacted]" in result.output
    assert "2703" not in result.output


def test_a_site_rule_fires(config: Path, tmp_path: Path) -> None:
    result = invoke(config, str(sample(tmp_path, "auth driver=88213")))
    assert "driver=[redacted]" in result.output
    assert result.exit_code == EXIT_FOUND


def test_the_tally_names_each_rule(config: Path, tmp_path: Path) -> None:
    result = invoke(config, str(sample(tmp_path, f"pan={VISA} driver=88213")))
    assert "card_pan" in result.output
    assert "driver_id" in result.output


def test_the_tally_counts_the_lines(config: Path, tmp_path: Path) -> None:
    result = invoke(config, str(sample(tmp_path, "one", "two", "three")))
    assert "3 lines, 0 removed" in result.output


def test_quiet_prints_only_the_tally(config: Path, tmp_path: Path) -> None:
    result = invoke(config, "--quiet", str(sample(tmp_path, "nozzle 1 lifted")))
    assert "nozzle 1 lifted" not in result.output
    assert "1 lines" in result.output


def test_explain_names_the_rules_that_fired(config: Path, tmp_path: Path) -> None:
    result = invoke(config, "--explain", str(sample(tmp_path, f"pan={VISA}")))
    assert "line 1: card_pan" in result.output


def test_explain_never_prints_what_was_removed(config: Path, tmp_path: Path) -> None:
    result = invoke(config, "--explain", str(sample(tmp_path, f"pan={VISA}")))
    assert VISA not in result.output


def test_explain_says_nothing_about_a_clean_line(config: Path, tmp_path: Path) -> None:
    result = invoke(config, "--explain", str(sample(tmp_path, "clean")))
    assert "line 1" not in result.output


def test_input_can_come_from_stdin(config: Path) -> None:
    result = invoke(config, stdin=f"pan={VISA}\n")
    assert "411111******1111" in result.output
    assert result.exit_code == EXIT_FOUND


def test_an_empty_stdin_is_clean(config: Path) -> None:
    result = invoke(config, stdin="")
    assert result.exit_code == EXIT_CLEAN
    assert "0 lines" in result.output


def test_a_missing_input_file_fails(config: Path, tmp_path: Path) -> None:
    result = invoke(config, str(tmp_path / "absent.log"))
    assert result.exit_code not in (EXIT_CLEAN, EXIT_FOUND)


def test_a_missing_config_fails(tmp_path: Path) -> None:
    result = CliRunner().invoke(cli, ["--config", str(tmp_path / "nope.yaml"), "redact"])
    assert "stanchion.config" in result.output


def test_a_disabled_pass_is_announced(tmp_path: Path) -> None:
    target = tmp_path / "site.yaml"
    target.write_text("site: XX-LAB-0001\nredact:\n  enabled: false\n", encoding="utf-8")
    result = invoke(target, stdin=f"pan={VISA}\n")
    assert "redaction is disabled" in result.output


def test_invalid_utf8_in_the_input_does_not_stop_the_run(config: Path, tmp_path: Path) -> None:
    target = tmp_path / "sample.log"
    target.write_bytes(b"frame \xff\xfe end\n")
    result = invoke(config, str(target))
    assert result.exit_code == EXIT_CLEAN


def test_several_lines_are_each_reported(config: Path, tmp_path: Path) -> None:
    result = invoke(config, "--explain", str(sample(tmp_path, "clean", f"pan={VISA}")))
    assert "line 2: card_pan" in result.output
    assert "line 1" not in result.output
