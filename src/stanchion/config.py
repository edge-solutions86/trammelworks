"""Site configuration.

One file per site, written at commissioning and then almost never touched. It
has to fail loudly at load rather than quietly at three in the morning, so every
field is validated here and there is no such thing as a partially valid config.

Defaults are chosen for the smallest box in the estate: a 32 GB industrial SSD
with a 4 GB partition for logs and a 2 GB monthly LTE allowance shared with the
payment traffic itself.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import timedelta
from pathlib import PurePosixPath, PureWindowsPath
from typing import Any

from stanchion.errors import ConfigError
from stanchion.identity import parse_site, parse_unit
from stanchion.ingest.multiline import MultilineRule, rules_from_mapping
from stanchion.redact.config import RedactConfig, redact_from_mapping
from stanchion.spool.config import SpoolSettings, spool_settings_from_mapping
from stanchion.units import parse_bytes, parse_duration

DEFAULT_LOG_ROOT = "/var/log/containers"
DEFAULT_POLL_INTERVAL = timedelta(seconds=2)
DEFAULT_READ_CHUNK = 64 * 1024
DEFAULT_MAX_LINE_BYTES = 1024 * 1024
DEFAULT_ENDPOINT = "https://collector.invalid/v1/ingest"
DEFAULT_MONTHLY_ALLOWANCE = 2 * 1000 * 1000 * 1000
DEFAULT_BATCH_BYTES = 256 * 1024
DEFAULT_ATTEMPT_TIMEOUT = timedelta(seconds=30)
DEFAULT_MAX_ATTEMPTS = 5


def _require_positive(name: str, value: int) -> int:
    if value <= 0:
        raise ConfigError(f"{name} must be positive", detail=str(value))
    return value


def is_absolute_path(raw: str) -> bool:
    """True for a path that is absolute under either convention.

    A site controller is Linux and every path in a real config is posix. The
    commissioning tool, though, runs on the field engineer's Windows laptop and
    validates the file it is about to push, so a drive rooted path has to be
    recognised as absolute rather than reported as a mistake it is not.
    """
    return PurePosixPath(raw).is_absolute() or PureWindowsPath(raw).is_absolute()


@dataclass(frozen=True, slots=True)
class IngestConfig:
    """Where the container runtime writes and how it is read."""

    log_root: str = DEFAULT_LOG_ROOT
    poll_interval: timedelta = DEFAULT_POLL_INTERVAL
    read_chunk: int = DEFAULT_READ_CHUNK
    max_line_bytes: int = DEFAULT_MAX_LINE_BYTES
    units: tuple[str, ...] = ()
    multiline: tuple[MultilineRule, ...] = ()

    def __post_init__(self) -> None:
        if not is_absolute_path(self.log_root):
            raise ConfigError("log_root must be an absolute path", detail=self.log_root)
        _require_positive("read_chunk", self.read_chunk)
        _require_positive("max_line_bytes", self.max_line_bytes)
        if self.poll_interval <= timedelta(0):
            raise ConfigError("poll_interval must be positive")
        if self.max_line_bytes < self.read_chunk:
            raise ConfigError(
                "max_line_bytes must be at least read_chunk",
                detail=f"{self.max_line_bytes} < {self.read_chunk}",
            )
        object.__setattr__(self, "units", tuple(parse_unit(u) for u in self.units))
        named = [rule.unit for rule in self.multiline]
        if len(set(named)) != len(named):
            raise ConfigError("two multiline rules name the same unit")


@dataclass(frozen=True, slots=True)
class ShipConfig:
    """The metered uplink to the estate collector."""

    endpoint: str = DEFAULT_ENDPOINT
    monthly_allowance_bytes: int = DEFAULT_MONTHLY_ALLOWANCE
    batch_bytes: int = DEFAULT_BATCH_BYTES
    attempt_timeout: timedelta = DEFAULT_ATTEMPT_TIMEOUT
    max_attempts: int = DEFAULT_MAX_ATTEMPTS

    def __post_init__(self) -> None:
        if not self.endpoint.startswith(("http://", "https://")):
            raise ConfigError("endpoint must be an http url", detail=self.endpoint)
        _require_positive("monthly_allowance_bytes", self.monthly_allowance_bytes)
        _require_positive("batch_bytes", self.batch_bytes)
        _require_positive("max_attempts", self.max_attempts)
        if self.batch_bytes > self.monthly_allowance_bytes:
            raise ConfigError("a single batch cannot exceed the monthly allowance")
        if self.attempt_timeout <= timedelta(0):
            raise ConfigError("attempt_timeout must be positive")


@dataclass(frozen=True, slots=True)
class SiteConfig:
    """Everything one forecourt controller needs to know about itself."""

    site: str
    ingest: IngestConfig = field(default_factory=IngestConfig)
    spool: SpoolSettings = field(default_factory=SpoolSettings)
    ship: ShipConfig = field(default_factory=ShipConfig)
    redact: RedactConfig = field(default_factory=RedactConfig)
    labels: dict[str, str] = field(default_factory=dict)

    def __post_init__(self) -> None:
        object.__setattr__(self, "site", parse_site(self.site))
        self.redact.check_site(self.site)
        for key, value in self.labels.items():
            if not key or not key.replace("_", "").replace("-", "").isalnum():
                raise ConfigError("malformed label key", detail=key)
            if not isinstance(value, str):
                raise ConfigError("label values must be strings", detail=key)

    def as_dict(self) -> dict[str, Any]:
        return {
            "site": self.site,
            "ingest": {
                "log_root": self.ingest.log_root,
                "poll_interval": self.ingest.poll_interval.total_seconds(),
                "read_chunk": self.ingest.read_chunk,
                "max_line_bytes": self.ingest.max_line_bytes,
                "units": list(self.ingest.units),
            },
            "redact": {
                "enabled": self.redact.enabled,
                "keep_first": self.redact.keep_first,
                "keep_last": self.redact.keep_last,
                "rules": {
                    rule.name: {
                        "pattern": rule.pattern,
                        "action": rule.action.value,
                        "keep_first": rule.keep_first,
                        "keep_last": rule.keep_last,
                    }
                    for rule in self.redact.rules
                },
            },
            "spool": {
                "directory": self.spool.directory,
                "budget": self.spool.budget_bytes,
                "segment": self.spool.segment_bytes,
                "reserve_ratio": self.spool.reserve_ratio,
                "sync_every": self.spool.sync_every,
                "compress": self.spool.compress,
            },
            "ship": {
                "endpoint": self.ship.endpoint,
                "monthly_allowance_bytes": self.ship.monthly_allowance_bytes,
                "batch_bytes": self.ship.batch_bytes,
                "attempt_timeout": self.ship.attempt_timeout.total_seconds(),
                "max_attempts": self.ship.max_attempts,
            },
            "labels": dict(self.labels),
        }


def ingest_from_mapping(raw: dict[str, Any]) -> IngestConfig:
    return IngestConfig(
        log_root=str(raw.get("log_root", DEFAULT_LOG_ROOT)),
        poll_interval=parse_duration(
            raw.get("poll_interval", DEFAULT_POLL_INTERVAL.total_seconds())
        ),
        read_chunk=parse_bytes(raw.get("read_chunk", DEFAULT_READ_CHUNK)),
        max_line_bytes=parse_bytes(raw.get("max_line_bytes", DEFAULT_MAX_LINE_BYTES)),
        units=tuple(raw.get("units", ())),
        multiline=tuple(rules_from_mapping(raw.get("multiline", {})).values()),
    )


def ship_from_mapping(raw: dict[str, Any]) -> ShipConfig:
    return ShipConfig(
        endpoint=str(raw.get("endpoint", DEFAULT_ENDPOINT)),
        monthly_allowance_bytes=parse_bytes(
            raw.get("monthly_allowance", DEFAULT_MONTHLY_ALLOWANCE)
        ),
        batch_bytes=parse_bytes(raw.get("batch", DEFAULT_BATCH_BYTES)),
        attempt_timeout=parse_duration(
            raw.get("attempt_timeout", DEFAULT_ATTEMPT_TIMEOUT.total_seconds())
        ),
        max_attempts=int(raw.get("max_attempts", DEFAULT_MAX_ATTEMPTS)),
    )


def site_from_mapping(raw: dict[str, Any]) -> SiteConfig:
    """Build a config from an already parsed mapping."""
    if "site" not in raw:
        raise ConfigError("config has no site id")
    unknown = set(raw) - {"site", "ingest", "spool", "ship", "redact", "labels"}
    if unknown:
        raise ConfigError("unknown config section", detail=", ".join(sorted(unknown)))
    return SiteConfig(
        site=str(raw["site"]),
        ingest=ingest_from_mapping(raw.get("ingest", {})),
        spool=spool_settings_from_mapping(raw.get("spool", {})),
        ship=ship_from_mapping(raw.get("ship", {})),
        redact=redact_from_mapping(raw.get("redact", {})),
        labels={str(k): str(v) for k, v in raw.get("labels", {}).items()},
    )
