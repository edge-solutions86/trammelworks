"""The systemd units a commissioned site runs.

These are generated rather than shipped as files, and the reason is the paths.
Every one of them comes out of the site config: the spool directory, the state
directory beside it, the archive, the log root that gets a read only bind. A
static unit file would name them again, and a unit that names a path the config
has moved is a site that starts, finds nothing, and reports healthy.

Three units, because they fail differently and want different restart policies.

* **collect** is the long running one. It reads and ships on a timer of its own
  and it must come back from anything, so it restarts always.
* **housekeep** is a timer. Retention, the cap and the vacuum, once a night, and
  a failed run is not urgent because tomorrow's run does the same work.
* **serve** is the read only query service, and it is optional. Sites that are
  never visited by an engineer with a laptop do not run it at all.

The hardening lines are not decoration. This process reads container logs that
contain payment traffic on a box in a cabinet in a car park, and the blast
radius of a bug in it should be one directory. ``ProtectSystem=strict`` with a
short ``ReadWritePaths`` is what makes that true, and the read only bind of the
log root is what stops a bug here writing into the runtime's own files.

``After=`` names the container runtime rather than ``network.target`` on
purpose. The uplink being down is the normal state of a forecourt and this
copes with it; the runtime not being up yet means the log root is empty and the
first discovery pass finds nothing.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from stanchion.config import SiteConfig
from stanchion.errors import ConfigError
from stanchion.site import state_directory

DEFAULT_USER = "stanchion"
DEFAULT_GROUP = "stanchion"
DEFAULT_EXECUTABLE = "/usr/local/bin/stanchion"
DEFAULT_CONFIG = "/etc/stanchion/site.yaml"
UNIT_DIRECTORY = "/etc/systemd/system"

#: What the runtime is called on the two images in the estate.
RUNTIME_UNITS = ("containerd.service", "docker.service")

COMMON_HARDENING = (
    "NoNewPrivileges=yes",
    "PrivateTmp=yes",
    "PrivateDevices=yes",
    "ProtectSystem=strict",
    "ProtectHome=yes",
    "ProtectKernelTunables=yes",
    "ProtectControlGroups=yes",
    "RestrictSUIDSGID=yes",
    "RestrictRealtime=yes",
    "LockPersonality=yes",
    "MemoryDenyWriteExecute=yes",
    "SystemCallArchitectures=native",
)


@dataclass(frozen=True, slots=True)
class Unit:
    """One generated file and where it belongs."""

    name: str
    body: str

    @property
    def path(self) -> PurePosixPath:
        return PurePosixPath(UNIT_DIRECTORY) / self.name

    def __str__(self) -> str:
        return self.name


@dataclass(frozen=True, slots=True)
class Deployment:
    """Everything the generator needs that is not in the site config."""

    config_path: str = DEFAULT_CONFIG
    executable: str = DEFAULT_EXECUTABLE
    user: str = DEFAULT_USER
    group: str = DEFAULT_GROUP
    serve: bool = False
    housekeep_at: str = "03:20"

    def __post_init__(self) -> None:
        if not self.config_path.startswith("/"):
            raise ConfigError("the config path in a unit must be absolute", detail=self.config_path)
        if not self.executable.startswith("/"):
            raise ConfigError("the executable in a unit must be absolute", detail=self.executable)
        if not _looks_like_a_time(self.housekeep_at):
            raise ConfigError("housekeep_at must be HH:MM", detail=self.housekeep_at)


def _looks_like_a_time(raw: str) -> bool:
    hours, _, minutes = raw.partition(":")
    if not (hours.isdigit() and minutes.isdigit()):
        return False
    return 0 <= int(hours) <= 23 and 0 <= int(minutes) <= 59


def unit_path(raw: str | Path) -> str:
    """A path as a unit file has to spell it, whatever generated it.

    Always POSIX. These files are for systemd, so they describe a Linux box
    however the engineer produced them, and a laptop that renders a backslash
    into ``ReadWritePaths=`` writes a unit that grants access to nothing and
    fails at the first write.
    """
    return PurePosixPath(Path(raw).as_posix()).as_posix()


def writable_paths(config: SiteConfig) -> list[str]:
    """Every directory this process has any business writing to.

    Directories rather than files, because the spool writes segment files that
    do not exist yet and systemd cannot grant write access to a path that is
    not there.
    """
    found = [
        unit_path(config.spool.directory),
        unit_path(state_directory(config.spool.directory)),
    ]
    if config.archive.enabled:
        found.append(unit_path(Path(config.archive.path).parent))
    return sorted(dict.fromkeys(found))


def _lines(*groups: str | Sequence[str]) -> str:
    """Join lines and groups of lines into a unit section."""
    out: list[str] = []
    for group in groups:
        if isinstance(group, str):
            out.append(group)
        else:
            out.extend(group)
    return "\n".join(out) + "\n"


def _service_body(
    config: SiteConfig, deployment: Deployment, description: str, command: str
) -> str:
    writes = " ".join(writable_paths(config))
    return _lines(
        "[Unit]",
        f"Description={description}",
        f"After={' '.join(RUNTIME_UNITS)}",
        f"Wants={' '.join(RUNTIME_UNITS)}",
        "",
        "[Service]",
        f"User={deployment.user}",
        f"Group={deployment.group}",
        f"ExecStart={deployment.executable} --config {unit_path(deployment.config_path)} {command}",
        f"ReadWritePaths={writes}",
        f"BindReadOnlyPaths={unit_path(config.ingest.log_root)}",
        COMMON_HARDENING,
    )


def collect_unit(config: SiteConfig, deployment: Deployment) -> Unit:
    """The long running collector."""
    body = _service_body(
        config,
        deployment,
        f"stanchion collector for {config.site}",
        "run",
    )
    body += _lines(
        "Restart=always",
        "RestartSec=15",
        "",
        "[Install]",
        "WantedBy=multi-user.target",
    )
    return Unit(name="stanchion-collect.service", body=body)


def housekeep_unit(config: SiteConfig, deployment: Deployment) -> Unit:
    """The nightly retention, cap and vacuum pass."""
    body = _service_body(
        config,
        deployment,
        f"stanchion housekeeping for {config.site}",
        "housekeep",
    )
    body += _lines("Type=oneshot")
    return Unit(name="stanchion-housekeep.service", body=body)


def housekeep_timer(deployment: Deployment) -> Unit:
    """When the housekeeping runs.

    ``Persistent=yes`` matters more than the hour does. A forecourt loses power
    and a site that was off at twenty past three would otherwise skip the pass
    entirely and wait a day, which on a card that is already full is the day it
    fills.
    """
    body = _lines(
        "[Unit]",
        "Description=stanchion nightly housekeeping",
        "",
        "[Timer]",
        f"OnCalendar=*-*-* {deployment.housekeep_at}:00",
        "RandomizedDelaySec=900",
        "Persistent=yes",
        "",
        "[Install]",
        "WantedBy=timers.target",
    )
    return Unit(name="stanchion-housekeep.timer", body=body)


def serve_unit(config: SiteConfig, deployment: Deployment) -> Unit:
    """The read only query service, for a site an engineer visits."""
    body = _service_body(
        config,
        deployment,
        f"stanchion read only query service for {config.site}",
        "serve",
    )
    body += _lines(
        "Restart=on-failure",
        "RestartSec=30",
        "",
        "[Install]",
        "WantedBy=multi-user.target",
    )
    return Unit(name="stanchion-serve.service", body=body)


def units_for(config: SiteConfig, deployment: Deployment | None = None) -> list[Unit]:
    """Every unit this site should have installed."""
    settings = deployment if deployment is not None else Deployment()
    found = [
        collect_unit(config, settings),
        housekeep_unit(config, settings),
        housekeep_timer(settings),
    ]
    if settings.serve:
        found.append(serve_unit(config, settings))
    return found


def write_units(units: list[Unit], directory: Path) -> list[Path]:
    """Write the generated units into a directory. Returns what was written."""
    directory.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    for unit in units:
        target = directory / unit.name
        target.write_text(unit.body, encoding="utf-8", newline="\n")
        written.append(target)
    return written
