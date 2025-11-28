from __future__ import annotations

from datetime import timedelta
from typing import Any

import pytest

from stanchion.archive.config import ArchiveSettings, archive_settings_from_mapping
from stanchion.config import SiteConfig, site_from_mapping
from stanchion.errors import ConfigError
from stanchion.spool.config import SpoolSettings


def test_the_defaults_are_on() -> None:
    built = ArchiveSettings()
    assert built.enabled is True
    assert built.file.name == "records.sqlite3"


def test_the_archive_can_be_turned_off() -> None:
    assert ArchiveSettings(enabled=False).enabled is False


def test_a_zero_delete_cap_is_refused() -> None:
    with pytest.raises(ConfigError, match="at least one"):
        ArchiveSettings(max_deletes=0)


def test_a_zero_size_cap_is_refused() -> None:
    with pytest.raises(ConfigError, match="max_bytes must be positive"):
        ArchiveSettings(max_bytes=0)


def test_a_zero_sweep_interval_is_refused() -> None:
    with pytest.raises(ConfigError, match="sweep_every must be positive"):
        ArchiveSettings(sweep_every=timedelta(0))


def test_an_archive_beside_the_spool_is_fine() -> None:
    ArchiveSettings(path="/var/lib/stanchion/archive/records.sqlite3").check_against_spool(
        "/var/lib/stanchion/spool"
    )


def test_an_archive_inside_the_spool_is_refused() -> None:
    built = ArchiveSettings(path="/var/lib/stanchion/spool/records.sqlite3")
    with pytest.raises(ConfigError, match="must not live inside the spool"):
        built.check_against_spool("/var/lib/stanchion/spool")


def test_an_archive_deeper_inside_the_spool_is_refused() -> None:
    built = ArchiveSettings(path="/var/lib/stanchion/spool/deep/records.sqlite3")
    with pytest.raises(ConfigError, match="must not live inside the spool"):
        built.check_against_spool("/var/lib/stanchion/spool")


def test_describe_names_the_path_and_the_windows() -> None:
    line = ArchiveSettings().describe()
    assert "records.sqlite3" in line
    assert "payment 180d" in line


def test_a_disabled_archive_says_so() -> None:
    assert ArchiveSettings(enabled=False).describe() == "archive off"


def test_the_mapping_builder_reads_the_section() -> None:
    raw: dict[str, Any] = {
        "path": "/data/archive.sqlite3",
        "max_bytes": "1GiB",
        "sweep_every": "2h",
        "retention": {"chatter": "3d"},
    }
    built = archive_settings_from_mapping(raw)
    assert built.path == "/data/archive.sqlite3"
    assert built.max_bytes == 1073741824
    assert built.sweep_every == timedelta(hours=2)
    assert built.retention.window_for("chatter") == timedelta(days=3)


def test_an_empty_section_gives_the_defaults() -> None:
    assert archive_settings_from_mapping({}) == ArchiveSettings()


def test_the_mapping_builder_rejects_an_unknown_key() -> None:
    with pytest.raises(ConfigError, match="unknown archive key"):
        archive_settings_from_mapping({"pth": "/data/x"})


def test_the_mapping_builder_needs_retention_to_be_a_mapping() -> None:
    with pytest.raises(ConfigError, match="retention section must be a mapping"):
        archive_settings_from_mapping({"retention": ["chatter"]})


def test_the_mapping_builder_still_enforces_the_scheme_floor() -> None:
    with pytest.raises(ConfigError, match="below the scheme floor"):
        archive_settings_from_mapping({"retention": {"payment": "1d"}})


def test_a_site_carries_an_archive_section() -> None:
    assert SiteConfig(site="US-TX-0417").archive.enabled is True


def test_a_site_refuses_an_archive_inside_its_spool() -> None:
    with pytest.raises(ConfigError, match="must not live inside the spool"):
        SiteConfig(
            site="US-TX-0417",
            spool=SpoolSettings(directory="/var/lib/stanchion/spool"),
            archive=ArchiveSettings(path="/var/lib/stanchion/spool/records.sqlite3"),
        )


def test_the_archive_section_is_read_from_a_config() -> None:
    built = site_from_mapping(
        {"site": "US-TX-0417", "archive": {"path": "/data/a.sqlite3", "enabled": False}}
    )
    assert built.archive.enabled is False
    assert built.archive.path == "/data/a.sqlite3"


def test_the_archive_section_appears_in_as_dict() -> None:
    payload = SiteConfig(site="US-TX-0417").as_dict()
    assert payload["archive"]["enabled"] is True
    assert payload["archive"]["path"].endswith("records.sqlite3")


def test_an_unknown_top_level_section_is_still_refused() -> None:
    with pytest.raises(ConfigError, match="unknown config section"):
        site_from_mapping({"site": "US-TX-0417", "archiv": {}})
