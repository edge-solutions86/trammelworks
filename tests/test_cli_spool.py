from __future__ import annotations

from pathlib import Path

import pytest
from click.testing import CliRunner, Result

from stanchion.cli.main import EXIT_OK, cli
from stanchion.records import LogRecord, Stream
from stanchion.spool.budget import Budget
from stanchion.spool.priority import PriorityTable
from stanchion.spool.store import Spool
from stanchion.timestamps import NanoTime

TABLE = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})


@pytest.fixture
def spool_dir(tmp_path: Path) -> Path:
    return tmp_path / "spool"


@pytest.fixture
def config(tmp_path: Path, spool_dir: Path) -> Path:
    target = tmp_path / "site.yaml"
    target.write_text(
        "site: US-TX-0417\n"
        "spool:\n"
        f"  directory: {spool_dir.as_posix()}\n"
        "  budget: 64KiB\n"
        "  segment: 1KiB\n"
        "  priority:\n"
        "    units:\n"
        "      payment-kernel: payment\n"
        "      price-sign: chatter\n",
        encoding="utf-8",
    )
    return target


def record(unit: str, message: str = "line") -> LogRecord:
    return LogRecord(
        site="US-TX-0417",
        unit=unit,
        stream=Stream.STDOUT,
        observed_at=NanoTime.parse("2025-03-04T12:00:00Z"),
        message=message,
    )


def fill(spool_dir: Path, unit: str, count: int) -> Spool:
    store = Spool(
        directory=spool_dir,
        budget=Budget(limit_bytes=64 * 1024, reserve_ratio=0.1),
        table=TABLE,
        segment_bytes=1024,
    )
    for index in range(count):
        store.append(record(unit, message=f"line {index} " + "x" * 40))
    store.seal_all()
    return store


def invoke(config: Path, *args: str) -> Result:
    return CliRunner().invoke(cli, ["--config", str(config), "spool", *args])


def test_an_empty_spool_reports_zero(config: Path) -> None:
    result = invoke(config)
    assert result.exit_code == EXIT_OK
    assert "spool 0/65536 bytes" in result.output


def test_the_usable_size_is_shown(config: Path) -> None:
    assert "usable" in invoke(config).output


def test_every_class_is_listed(config: Path) -> None:
    output = invoke(config).output
    assert "payment" in output
    assert "standard" in output
    assert "chatter" in output


def test_the_protected_class_is_marked(config: Path) -> None:
    assert "protected" in invoke(config).output


def test_the_used_bytes_follow_what_was_written(config: Path, spool_dir: Path) -> None:
    fill(spool_dir, "price-sign", 6)
    assert "spool 0/" not in invoke(config).output


def test_the_class_breakdown_shows_where_it_went(config: Path, spool_dir: Path) -> None:
    fill(spool_dir, "payment-kernel", 6)
    output = invoke(config).output
    lines = [line for line in output.splitlines() if line.strip().startswith("payment")]
    assert lines and "0B" not in lines[0]


def test_evictable_excludes_the_protected_class(config: Path, spool_dir: Path) -> None:
    fill(spool_dir, "payment-kernel", 6)
    assert "evictable 0B" in invoke(config).output


def test_evictable_counts_a_low_class(config: Path, spool_dir: Path) -> None:
    fill(spool_dir, "price-sign", 6)
    assert "evictable 0B" not in invoke(config).output


def test_segments_are_listed_on_request(config: Path, spool_dir: Path) -> None:
    fill(spool_dir, "price-sign", 6)
    output = invoke(config, "--segments").output
    assert "-chatter.spool" in output


def test_an_empty_segment_list_says_so(config: Path) -> None:
    assert "no segments" in invoke(config, "--segments").output


def test_verify_reads_every_segment(config: Path, spool_dir: Path) -> None:
    fill(spool_dir, "price-sign", 6)
    output = invoke(config, "--verify").output
    assert "0 damaged" in output
    assert "records" in output


def test_verify_reports_a_damaged_segment(config: Path, spool_dir: Path) -> None:
    fill(spool_dir, "price-sign", 6)
    victim = sorted(spool_dir.glob("*.spool"))[0]
    data = bytearray(victim.read_bytes())
    data[10] ^= 0xFF
    victim.write_bytes(bytes(data))
    output = invoke(config, "--verify").output
    assert "DAMAGED" in output
    assert "1 damaged" in output


def test_a_plan_lists_what_would_go(config: Path, spool_dir: Path) -> None:
    fill(spool_dir, "price-sign", 12)
    output = invoke(config, "--plan", "1KiB").output
    assert "to free 1KiB" in output
    assert "delete" in output


def test_a_plan_that_is_enough_says_so(config: Path, spool_dir: Path) -> None:
    fill(spool_dir, "price-sign", 12)
    assert "enough" in invoke(config, "--plan", "512B").output


def test_a_plan_that_is_not_enough_reports_the_shortfall(config: Path, spool_dir: Path) -> None:
    fill(spool_dir, "payment-kernel", 12)
    output = invoke(config, "--plan", "8KiB").output
    assert "short by" in output
    assert "protected and cannot go" in output


def test_a_malformed_plan_size_is_refused(config: Path) -> None:
    result = invoke(config, "--plan", "loads")
    assert result.exit_code != EXIT_OK


def test_a_missing_config_fails(tmp_path: Path) -> None:
    result = CliRunner().invoke(cli, ["--config", str(tmp_path / "nope.yaml"), "spool"])
    assert "stanchion.config" in result.output
