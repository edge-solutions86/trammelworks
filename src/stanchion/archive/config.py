"""The ``archive`` section.

Two settings here need saying out loud.

**The archive can be turned off.** Some sites in the estate are unattended
compressor stations with no card reader and no disputes, and on those the
archive is a write amplifier on an SD card that has to last five years. Turning
it off is a real choice, and the config says so rather than the operator setting
retention to nothing and hoping.

**The cap is not the same thing as retention.** Retention says what may be
deleted. The cap says what has to be, and on a card that is filling up those
give different answers: a site can be inside every window it has and still out
of room. Trimming past retention is the last thing tried and the config says so
out loud, because a number that quietly deletes records nobody agreed to delete
is not a number to leave implicit.

**The archive path is not inside the spool.** It has its own directory, checked
here, because everything under the spool is fair game for an eviction sweep and
an eviction that deletes the dispute archive is the worst bug this system could
have.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import timedelta
from pathlib import Path, PurePosixPath
from typing import Any

from stanchion.archive.retention import RetentionPolicy, policy_from_mapping
from stanchion.errors import ConfigError
from stanchion.units import format_bytes, parse_bytes

DEFAULT_PATH = "/var/lib/stanchion/archive/records.sqlite3"
DEFAULT_MAX_DELETES = 50_000
DEFAULT_SWEEP_EVERY = timedelta(hours=6)
DEFAULT_MAX_BYTES = 2 * 1024 * 1024 * 1024
DEFAULT_VACUUM_MIN_RECLAIM = 32 * 1024 * 1024


@dataclass(frozen=True, slots=True)
class ArchiveSettings:
    """Where the archive lives and how long it keeps things."""

    enabled: bool = True
    path: str = DEFAULT_PATH
    max_bytes: int = DEFAULT_MAX_BYTES
    max_deletes: int = DEFAULT_MAX_DELETES
    sweep_every: timedelta = DEFAULT_SWEEP_EVERY
    trim_to_cap: bool = False
    vacuum: bool = True
    vacuum_min_reclaim_bytes: int = DEFAULT_VACUUM_MIN_RECLAIM
    retention: RetentionPolicy = field(default_factory=RetentionPolicy)

    def __post_init__(self) -> None:
        if self.max_deletes < 1:
            raise ConfigError("max_deletes must be at least one", detail=str(self.max_deletes))
        if self.max_bytes <= 0:
            raise ConfigError("archive max_bytes must be positive", detail=str(self.max_bytes))
        if self.sweep_every <= timedelta(0):
            raise ConfigError("sweep_every must be positive")
        if self.vacuum_min_reclaim_bytes < 0:
            raise ConfigError(
                "vacuum_min_reclaim cannot be negative",
                detail=str(self.vacuum_min_reclaim_bytes),
            )

    @property
    def file(self) -> Path:
        return Path(self.path)

    def check_against_spool(self, spool_directory: str) -> None:
        """Refuse an archive that an eviction sweep could reach."""
        spool = PurePosixPath(spool_directory)
        target = PurePosixPath(Path(self.path).as_posix())
        if spool in target.parents or target.parent == spool:
            raise ConfigError(
                "the archive must not live inside the spool directory", detail=self.path
            )

    def describe(self) -> str:
        if not self.enabled:
            return "archive off"
        end = ", trims to fit" if self.trim_to_cap else ""
        return (
            f"archive {self.path}, {self.retention.describe()}, "
            f"cap {format_bytes(self.max_bytes)}{end}"
        )


def archive_settings_from_mapping(raw: dict[str, Any]) -> ArchiveSettings:
    """Build the section from the config block."""
    unknown = set(raw) - {
        "enabled",
        "path",
        "max_bytes",
        "max_deletes",
        "sweep_every",
        "trim_to_cap",
        "vacuum",
        "vacuum_min_reclaim",
        "retention",
    }
    if unknown:
        raise ConfigError("unknown archive key", detail=", ".join(sorted(unknown)))
    retention_raw = raw.get("retention", {})
    if not isinstance(retention_raw, dict):
        raise ConfigError("the retention section must be a mapping")
    from stanchion.units import parse_duration

    return ArchiveSettings(
        enabled=bool(raw.get("enabled", True)),
        path=str(raw.get("path", DEFAULT_PATH)),
        max_bytes=parse_bytes(raw.get("max_bytes", DEFAULT_MAX_BYTES)),
        max_deletes=int(raw.get("max_deletes", DEFAULT_MAX_DELETES)),
        sweep_every=parse_duration(raw.get("sweep_every", DEFAULT_SWEEP_EVERY.total_seconds())),
        trim_to_cap=bool(raw.get("trim_to_cap", False)),
        vacuum=bool(raw.get("vacuum", True)),
        vacuum_min_reclaim_bytes=parse_bytes(
            raw.get("vacuum_min_reclaim", DEFAULT_VACUUM_MIN_RECLAIM)
        ),
        retention=policy_from_mapping(retention_raw),
    )
