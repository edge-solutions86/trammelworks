from __future__ import annotations

from pathlib import Path

import pytest
from click.testing import CliRunner, Result

from stanchion.cli.clock_cmd import _state_dir
from stanchion.cli.main import EXIT_OK, cli
from stanchion.clockfix.session import SessionLedger, SyncEvent
from stanchion.clockfix.store import ClockStore
from stanchion.timestamps import NS_PER_SECOND, NanoTime

BOOT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d"
OTHER = "aa" * 16


@pytest.fixture
def spool(tmp_path: Path) -> Path:
    return tmp_path / "spool"


@pytest.fixture
def config(tmp_path: Path, spool: Path) -> Path:
    target = tmp_path / "site.yaml"
    target.write_text(
        f"site: US-TX-0417\nspool:\n  directory: {spool.as_posix()}\n", encoding="utf-8"
    )
    return target


@pytest.fixture
def store(spool: Path) -> ClockStore:
    return ClockStore(directory=Path(_state_dir(spool.as_posix())))


def sync(boot_id: str, monotonic_ns: int, error_seconds: float) -> SyncEvent:
    truth = NanoTime.parse("2025-03-04T12:00:00Z")
    return SyncEvent(
        boot_id=boot_id,
        monotonic_ns=monotonic_ns,
        before=truth.shifted(-int(error_seconds * NS_PER_SECOND)),
        after=truth,
        source="chrony",
    )


def invoke(config: Path, *args: str) -> Result:
    return CliRunner().invoke(cli, ["--config", str(config), "clock", *args])


def test_the_state_dir_sits_beside_the_spool() -> None:
    assert _state_dir("/var/lib/stanchion/spool") == "/var/lib/stanchion/state"


def test_the_state_dir_is_never_inside_the_spool() -> None:
    assert "spool" not in _state_dir("/var/lib/stanchion/spool").rsplit("/", 1)[-1]


def test_an_empty_ledger_says_so(config: Path) -> None:
    result = invoke(config)
    assert result.exit_code == EXIT_OK
    assert "no boot sessions recorded" in result.output


def test_the_site_is_named(config: Path, store: ClockStore) -> None:
    ledger = SessionLedger()
    ledger.add_sync(sync(BOOT, 600 * NS_PER_SECOND, 7))
    store.save(ledger)
    assert "site US-TX-0417" in invoke(config).output


def test_the_boot_id_is_shown(config: Path, store: ClockStore) -> None:
    ledger = SessionLedger()
    ledger.add_sync(sync(BOOT, 600 * NS_PER_SECOND, 7))
    store.save(ledger)
    assert BOOT in invoke(config).output


def test_a_sync_is_listed_with_its_drift_in_milliseconds(config: Path, store: ClockStore) -> None:
    ledger = SessionLedger()
    ledger.add_sync(sync(BOOT, 600 * NS_PER_SECOND, 7))
    store.save(ledger)
    output = invoke(config).output
    assert "chrony" in output
    assert "+7000.0 ms" in output


def test_a_large_correction_is_flagged(config: Path, store: ClockStore) -> None:
    ledger = SessionLedger()
    ledger.add_sync(sync(BOOT, 600 * NS_PER_SECOND, 7200))
    store.save(ledger)
    assert "LARGE" in invoke(config).output


def test_a_small_correction_is_not_flagged(config: Path, store: ClockStore) -> None:
    ledger = SessionLedger()
    ledger.add_sync(sync(BOOT, 600 * NS_PER_SECOND, 1))
    store.save(ledger)
    assert "LARGE" not in invoke(config).output


def test_one_sync_gives_no_rate(config: Path, store: ClockStore) -> None:
    ledger = SessionLedger()
    ledger.add_sync(sync(BOOT, 600 * NS_PER_SECOND, 7))
    store.save(ledger)
    assert "rate unknown" in invoke(config).output


def test_two_syncs_give_a_rate_in_ppm(config: Path, store: ClockStore) -> None:
    ledger = SessionLedger()
    ledger.add_sync(sync(BOOT, 0, 0))
    ledger.add_sync(sync(BOOT, 1000 * NS_PER_SECOND, 1))
    store.save(ledger)
    assert "+1000.0 ppm" in invoke(config).output


def test_only_the_latest_boot_is_shown_by_default(config: Path, store: ClockStore) -> None:
    ledger = SessionLedger()
    ledger.add_sync(sync(BOOT, 600 * NS_PER_SECOND, 7))
    ledger.add_sync(sync(OTHER, 600 * NS_PER_SECOND, 3))
    store.save(ledger)
    output = invoke(config).output
    assert OTHER in output
    assert BOOT not in output


def test_all_boots_shows_every_one(config: Path, store: ClockStore) -> None:
    ledger = SessionLedger()
    ledger.add_sync(sync(BOOT, 600 * NS_PER_SECOND, 7))
    ledger.add_sync(sync(OTHER, 600 * NS_PER_SECOND, 3))
    store.save(ledger)
    output = invoke(config, "--all-boots").output
    assert BOOT in output
    assert OTHER in output


def test_the_restart_count_is_reported(config: Path, store: ClockStore) -> None:
    ledger = SessionLedger()
    ledger.session(BOOT)
    ledger.session(OTHER)
    store.save(ledger)
    assert "2 boots remembered, 1 restarts" in invoke(config).output


def test_a_corrupt_ledger_is_reported_and_not_fatal(config: Path, store: ClockStore) -> None:
    ledger = SessionLedger()
    ledger.session(BOOT)
    store.save(ledger)
    store.path.write_text("{broken", encoding="utf-8")
    result = invoke(config)
    assert result.exit_code == EXIT_OK
    assert "no boot sessions recorded" in result.output


def test_a_missing_config_fails_cleanly(tmp_path: Path) -> None:
    result = CliRunner().invoke(cli, ["--config", str(tmp_path / "nope.yaml"), "clock"])
    assert result.exit_code != EXIT_OK
    assert "stanchion.config" in result.output
