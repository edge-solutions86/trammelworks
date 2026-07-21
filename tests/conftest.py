from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import pytest

from stanchion.archive.config import DEFAULT_PATH as DEFAULT_ARCHIVE_PATH
from stanchion.archive.config import ArchiveSettings
from stanchion.cli.main import load_commands
from stanchion.clocks import ManualClock
from stanchion.config import IngestConfig, SiteConfig
from stanchion.eventlog import EventLog, Level
from stanchion.spool.config import DEFAULT_DIRECTORY as DEFAULT_SPOOL_DIRECTORY
from stanchion.spool.config import SpoolSettings
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
        spool=SpoolSettings(directory=(tmp_path / "spool").as_posix()),
        archive=ArchiveSettings(path=(tmp_path / "archive" / "records.sqlite3").as_posix()),
    )


@pytest.fixture(autouse=True, scope="session")
def _commands_are_attached() -> None:
    """The CLI group only carries its subcommands once they are imported."""
    load_commands()


#: The config defaults are real absolute paths, because they are where this
#: software actually lives on a commissioned box. A test that leaves a section
#: at its default therefore writes to the machine running the suite.
DEFAULT_PATHS = (
    Path(DEFAULT_SPOOL_DIRECTORY).parent,
    Path(DEFAULT_ARCHIVE_PATH).parent,
)


@pytest.fixture(autouse=True, scope="session")
def _nothing_escapes_the_temporary_directory() -> Iterator[None]:
    """Refuse to finish if the suite wrote to where a real site would.

    This is here because the suite passed on Windows for months while doing
    exactly that. The defaults are POSIX absolute, so on Windows they land
    under the current drive and are writable; on Linux they are ``/var/lib``
    and the run fails with a permission error. Same bug, and only one of the
    two platforms said so.

    Checked once for the session rather than per test. It costs two stat calls
    and it catches the whole class.
    """
    before = {path: path.exists() for path in DEFAULT_PATHS}
    yield
    made = [str(path) for path, existed in before.items() if not existed and path.exists()]
    if made:
        raise AssertionError("the suite wrote outside its temporary directory: " + ", ".join(made))
