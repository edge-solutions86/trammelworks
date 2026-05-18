from __future__ import annotations

from pathlib import Path

import pytest
from click.testing import CliRunner, Result

from stanchion.archive.config import ArchiveSettings
from stanchion.cli.main import EXIT_OK, cli
from stanchion.config import IngestConfig, SiteConfig
from stanchion.deploy import (
    COMMON_HARDENING,
    Deployment,
    Unit,
    collect_unit,
    housekeep_timer,
    housekeep_unit,
    serve_unit,
    units_for,
    writable_paths,
    write_units,
)
from stanchion.errors import ConfigError
from stanchion.spool.config import SpoolSettings


def config_for(tmp_path: Path, **over: object) -> SiteConfig:
    settings: dict[str, object] = {
        "site": "US-TX-0417",
        "ingest": IngestConfig(log_root="/var/log/containers"),
        "spool": SpoolSettings(directory="/var/lib/stanchion/spool"),
        "archive": ArchiveSettings(path="/var/lib/stanchion/archive/records.sqlite3"),
    }
    settings.update(over)
    return SiteConfig(**settings)  # type: ignore[arg-type]


@pytest.fixture
def config(tmp_path: Path) -> SiteConfig:
    return config_for(tmp_path)


def body_of(units: list[Unit], name: str) -> str:
    return next(unit.body for unit in units if unit.name == name)


def test_a_relative_config_path_is_refused() -> None:
    with pytest.raises(ConfigError, match="must be absolute"):
        Deployment(config_path="site.yaml")


def test_a_relative_executable_is_refused() -> None:
    with pytest.raises(ConfigError, match="must be absolute"):
        Deployment(executable="stanchion")


@pytest.mark.parametrize("bad", ["3", "25:00", "03:99", "morning", "03-20"])
def test_a_time_that_is_not_a_time_is_refused(bad: str) -> None:
    with pytest.raises(ConfigError, match="HH:MM"):
        Deployment(housekeep_at=bad)


def test_a_sensible_time_is_taken() -> None:
    assert Deployment(housekeep_at="23:59").housekeep_at == "23:59"


def test_the_writable_paths_cover_the_spool_and_its_state(config: SiteConfig) -> None:
    found = writable_paths(config)
    assert "/var/lib/stanchion/spool" in found
    assert "/var/lib/stanchion/state" in found


def test_the_writable_paths_cover_the_archive_directory(config: SiteConfig) -> None:
    assert "/var/lib/stanchion/archive" in writable_paths(config)


def test_a_site_with_no_archive_does_not_ask_to_write_one(tmp_path: Path) -> None:
    config = config_for(tmp_path, archive=ArchiveSettings(enabled=False))
    assert not any("archive" in item for item in writable_paths(config))


def test_the_writable_paths_have_no_duplicates(config: SiteConfig) -> None:
    found = writable_paths(config)
    assert len(found) == len(set(found))


def test_the_collector_unit_runs_the_run_command(config: SiteConfig) -> None:
    body = collect_unit(config, Deployment()).body
    assert "ExecStart=/usr/local/bin/stanchion --config /etc/stanchion/site.yaml run" in body


def test_the_collector_unit_names_the_site(config: SiteConfig) -> None:
    assert "US-TX-0417" in collect_unit(config, Deployment()).body


def test_the_collector_restarts_always(config: SiteConfig) -> None:
    assert "Restart=always" in collect_unit(config, Deployment()).body


def test_the_collector_waits_for_the_runtime(config: SiteConfig) -> None:
    body = collect_unit(config, Deployment()).body
    assert "After=containerd.service docker.service" in body


def test_every_service_unit_is_hardened(config: SiteConfig) -> None:
    for unit in (collect_unit, housekeep_unit, serve_unit):
        body = unit(config, Deployment()).body
        for line in COMMON_HARDENING:
            assert line in body


def test_the_log_root_is_bound_read_only(config: SiteConfig) -> None:
    assert "BindReadOnlyPaths=/var/log/containers" in collect_unit(config, Deployment()).body


def test_the_spool_is_writable(config: SiteConfig) -> None:
    body = collect_unit(config, Deployment()).body
    assert "ReadWritePaths=" in body
    assert "/var/lib/stanchion/spool" in body


def test_the_log_root_is_not_writable(config: SiteConfig) -> None:
    line = next(
        row
        for row in collect_unit(config, Deployment()).body.splitlines()
        if row.startswith("ReadWritePaths=")
    )
    assert "/var/log/containers" not in line


def test_the_housekeep_unit_is_a_oneshot(config: SiteConfig) -> None:
    assert "Type=oneshot" in housekeep_unit(config, Deployment()).body


def test_the_housekeep_unit_runs_the_housekeep_command(config: SiteConfig) -> None:
    assert " housekeep" in housekeep_unit(config, Deployment()).body


def test_the_timer_uses_the_hour_it_was_given() -> None:
    assert "OnCalendar=*-*-* 04:05:00" in housekeep_timer(Deployment(housekeep_at="04:05")).body


def test_the_timer_catches_up_after_a_power_cut() -> None:
    assert "Persistent=yes" in housekeep_timer(Deployment()).body


def test_the_timer_spreads_the_estate_out() -> None:
    assert "RandomizedDelaySec=" in housekeep_timer(Deployment()).body


def test_three_units_by_default(config: SiteConfig) -> None:
    assert [unit.name for unit in units_for(config)] == [
        "stanchion-collect.service",
        "stanchion-housekeep.service",
        "stanchion-housekeep.timer",
    ]


def test_the_service_is_added_when_asked(config: SiteConfig) -> None:
    names = [unit.name for unit in units_for(config, Deployment(serve=True))]
    assert "stanchion-serve.service" in names


def test_the_serve_unit_restarts_only_on_failure(config: SiteConfig) -> None:
    assert "Restart=on-failure" in serve_unit(config, Deployment()).body


def test_a_unit_knows_where_it_belongs(config: SiteConfig) -> None:
    unit = collect_unit(config, Deployment())
    assert str(unit.path) == "/etc/systemd/system/stanchion-collect.service"
    assert str(unit) == "stanchion-collect.service"


def test_every_unit_ends_with_a_newline(config: SiteConfig) -> None:
    for unit in units_for(config, Deployment(serve=True)):
        assert unit.body.endswith("\n")


def test_every_unit_has_an_install_section_except_the_oneshot(config: SiteConfig) -> None:
    for unit in units_for(config, Deployment(serve=True)):
        if unit.name == "stanchion-housekeep.service":
            assert "[Install]" not in unit.body
        else:
            assert "[Install]" in unit.body


def test_units_are_written_where_asked(config: SiteConfig, tmp_path: Path) -> None:
    written = write_units(units_for(config), tmp_path / "units")
    assert len(written) == 3
    assert all(path.exists() for path in written)


def test_writing_creates_the_directory(config: SiteConfig, tmp_path: Path) -> None:
    write_units(units_for(config), tmp_path / "deep" / "units")
    assert (tmp_path / "deep" / "units").is_dir()


def test_the_written_unit_matches_the_generated_one(config: SiteConfig, tmp_path: Path) -> None:
    unit = units_for(config)[0]
    written = write_units([unit], tmp_path / "units")[0]
    assert written.read_text(encoding="utf-8") == unit.body


@pytest.fixture
def config_file(tmp_path: Path) -> Path:
    target = tmp_path / "site.yaml"
    target.write_text(
        "site: US-TX-0417\n"
        "ingest:\n"
        "  log_root: /var/log/containers\n"
        "spool:\n"
        "  directory: /var/lib/stanchion/spool\n"
        "archive:\n"
        "  path: /var/lib/stanchion/archive/records.sqlite3\n",
        encoding="utf-8",
    )
    return target


def invoke(config: Path, *args: str) -> Result:
    return CliRunner().invoke(cli, ["--config", str(config), "units", *args])


def test_the_command_prints_the_units(config_file: Path) -> None:
    result = invoke(config_file)
    assert result.exit_code == EXIT_OK
    assert "/etc/systemd/system/stanchion-collect.service" in result.output
    assert "ExecStart=" in result.output


def test_the_command_names_the_installed_path_by_default(config_file: Path) -> None:
    # The working copy on a laptop is not where the file lives on the box.
    result = invoke(config_file)
    assert "--config /etc/stanchion/site.yaml" in result.output
    assert "pass --config-path to change it" in result.output


def test_the_command_keeps_an_absolute_path_it_was_given(config_file: Path) -> None:
    result = invoke(config_file, "--config-path", "/etc/stanchion/site.yaml")
    assert "--config /etc/stanchion/site.yaml" in result.output
    assert "pass --config-path" not in result.output


def test_a_unit_never_carries_a_backslash(config_file: Path, tmp_path: Path) -> None:
    # These files describe a Linux box whatever generated them, so the written
    # units are what this checks rather than the notes on the way past.
    invoke(config_file, "--to", str(tmp_path / "units"))
    for path in (tmp_path / "units").iterdir():
        assert "\\" not in path.read_text(encoding="utf-8")


def test_the_command_can_be_told_another_config_path(config_file: Path) -> None:
    assert (
        "--config /etc/stanchion/other.yaml"
        in invoke(config_file, "--config-path", "/etc/stanchion/other.yaml").output
    )


def test_the_command_writes_to_a_directory(config_file: Path, tmp_path: Path) -> None:
    result = invoke(config_file, "--to", str(tmp_path / "units"))
    assert "3 units written, none installed" in result.output
    assert (tmp_path / "units" / "stanchion-collect.service").exists()


def test_the_command_adds_the_service_when_asked(config_file: Path) -> None:
    assert "stanchion-serve.service" in invoke(config_file, "--serve").output


def test_the_command_takes_a_user(config_file: Path) -> None:
    output = invoke(config_file, "--user", "forecourt").output
    assert "User=forecourt" in output
    assert "Group=forecourt" in output


def test_a_bad_time_is_refused_by_the_command(config_file: Path) -> None:
    result = invoke(config_file, "--at", "half three")
    assert result.exit_code != EXIT_OK
    assert "HH:MM" in result.output


def test_nothing_is_installed_by_the_command(config_file: Path, tmp_path: Path) -> None:
    # The command must never touch the real unit directory.
    invoke(config_file, "--to", str(tmp_path / "units"))
    assert not Path("/etc/systemd/system/stanchion-collect.service").exists()
