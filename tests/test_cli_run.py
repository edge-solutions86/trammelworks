from __future__ import annotations

from pathlib import Path

import pytest
from click.testing import CliRunner, Result

from stanchion.cli.main import EXIT_OK, cli
from stanchion.cli.run_cmd import RunTotals
from stanchion.ship.session import ShipReport
from stanchion.site import CollectReport, CycleReport
from tests.support.logfiles import SiteTree, cri_full

TS = "2025-03-04T12:00:00.000000000Z"
VISA = "4111111111111111"


@pytest.fixture
def outbox(tmp_path: Path) -> Path:
    return tmp_path / "outbox"


@pytest.fixture
def config(tmp_path: Path, tree: SiteTree, outbox: Path) -> Path:
    tree.root.mkdir(parents=True, exist_ok=True)
    target = tmp_path / "site.yaml"
    target.write_text(
        "site: US-TX-0417\n"
        "ingest:\n"
        f"  log_root: {tree.root.as_posix()}\n"
        "spool:\n"
        f"  directory: {(tmp_path / 'spool').as_posix()}\n"
        "  budget: 1MiB\n"
        "  segment: 16KiB\n"
        "ship:\n"
        f"  endpoint: file://{outbox.as_posix()}\n"
        "  interval: 1s\n",
        encoding="utf-8",
    )
    return target


def invoke(config: Path, *args: str) -> Result:
    return CliRunner().invoke(cli, ["--config", str(config), *args])


def test_totals_start_empty() -> None:
    totals = RunTotals()
    assert "0 cycles" in str(totals)


def test_totals_add_a_cycle_up() -> None:
    totals = RunTotals()
    totals.absorb(
        CycleReport(
            collect=CollectReport(read=3, shipped_to_spool=3, redacted=1),
            ship=ShipReport(records_sent=3, bytes_sent=400),
        )
    )
    assert "read 3" in str(totals)
    assert "sent 3 in 400 bytes" in str(totals)


def test_totals_remember_why_shipping_stopped() -> None:
    totals = RunTotals()
    totals.absorb(CycleReport(ship=ShipReport(stopped="gateway")))
    assert totals.stopped == "gateway"


def test_a_run_on_an_empty_site_reports_nothing(config: Path) -> None:
    result = invoke(config, "run")
    assert result.exit_code == EXIT_OK
    assert "1 cycles, read 0" in result.output


def test_a_run_collects_and_ships(config: Path, tree: SiteTree, outbox: Path) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "nozzle 1 lifted")])
    result = invoke(config, "run", "--interval", "0")
    assert "read 1" in result.output
    assert "sent 1" in result.output
    assert list(outbox.glob("*.json"))


def test_the_shipped_file_holds_the_record(config: Path, tree: SiteTree, outbox: Path) -> None:
    import json

    tree.write_cri("pump-bridge", [cri_full(TS, "nozzle 1 lifted")])
    invoke(config, "run", "--interval", "0")
    payload = json.loads(next(iter(outbox.glob("*.json"))).read_text(encoding="utf-8"))
    assert payload["records"][0]["message"] == "nozzle 1 lifted"


def test_a_card_number_does_not_reach_the_outbox(
    config: Path, tree: SiteTree, outbox: Path
) -> None:
    tree.write_cri("payment-kernel", [cri_full(TS, f"pan={VISA}")])
    invoke(config, "run", "--interval", "0")
    written = next(iter(outbox.glob("*.json"))).read_text(encoding="utf-8")
    assert VISA not in written


def test_several_cycles_are_run(config: Path, tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    result = invoke(config, "run", "--cycles", "3", "--interval", "0")
    assert "3 cycles" in result.output


def test_zero_cycles_is_refused(config: Path) -> None:
    result = invoke(config, "run", "--cycles", "0")
    assert result.exit_code != EXIT_OK
    assert "at least 1" in result.output


def test_verbose_reports_each_cycle(config: Path, tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    result = CliRunner().invoke(cli, ["--config", str(config), "-v", "run", "--interval", "0"])
    assert "cycle 1:" in result.output


def test_a_missing_config_fails(tmp_path: Path) -> None:
    result = CliRunner().invoke(cli, ["--config", str(tmp_path / "nope.yaml"), "run"])
    assert result.exit_code != EXIT_OK
    assert "stanchion.config" in result.output


def test_ship_on_an_empty_spool_sends_nothing(config: Path) -> None:
    result = invoke(config, "ship")
    assert "sent 0 records" in result.output


def test_ship_drains_what_run_collected(config: Path, tree: SiteTree, outbox: Path) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    CliRunner().invoke(cli, ["--config", str(config), "run", "--interval", "0"])
    tree.write_cri("pump-bridge", [cri_full(TS, "two")])
    CliRunner().invoke(cli, ["--config", str(config), "run", "--interval", "0"])
    assert len(list(outbox.glob("*.json"))) == 2


def test_ship_reports_the_meter(config: Path) -> None:
    assert "meter" in invoke(config, "ship").output


def test_zero_passes_is_refused(config: Path) -> None:
    result = invoke(config, "ship", "--passes", "0")
    assert result.exit_code != EXIT_OK
    assert "at least 1" in result.output


def test_ship_stops_early_when_there_is_nothing_left(config: Path, tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    CliRunner().invoke(cli, ["--config", str(config), "run", "--interval", "0"])
    result = invoke(config, "ship", "--passes", "5")
    assert "sent 0 records" in result.output
