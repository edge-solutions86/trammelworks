from __future__ import annotations

from pathlib import Path

import pytest

from stanchion.cli.main import load_commands
from stanchion.clocks import ManualClock
from stanchion.config import IngestConfig, SiteConfig, SpoolConfig
from stanchion.eventlog import EventLog, Level
from stanchion.telemetry import Registry
from tests.support.logfiles import SiteTree

SITE = "US-TX-0417"


@pytest.fixture
def clock() -> ManualClock:
    driver = ManualClock()
    driver.set_wall("2025-03-04T11:30:00Z")
    return driver


@pytest.fixture
def registry() -> Registry:
    return Registry()


@pytest.fixture
def events(clock: ManualClock) -> EventLog:
    return EventLog(minimum=Level.DEBUG, clock=clock)


@pytest.fixture
def tree(tmp_path: Path) -> SiteTree:
    return SiteTree(root=tmp_path / "log")


@pytest.fixture
def site_config(tmp_path: Path, tree: SiteTree) -> SiteConfig:
    return SiteConfig(
        site=SITE,
        ingest=IngestConfig(log_root=tree.root.as_posix()),
        spool=SpoolConfig(directory=(tmp_path / "spool").as_posix()),
    )


@pytest.fixture(autouse=True, scope="session")
def _commands_are_attached() -> None:
    """The CLI group only carries its subcommands once they are imported."""
    load_commands()
