"""The ``ship`` section, and keeping the meter across a restart.

The meter is the one piece of shipper state that must survive a reboot. A
forecourt that loses power twice a week would otherwise start every month fresh
several times over, and the estate would find out from the bill.

Acknowledgement watermarks survive too, but for a different reason: losing them
is safe and merely expensive. Everything gets sent again, the collector dedupes
on the batch id, and the only cost is metered bytes. Losing the meter is not
safe, because nothing downstream would notice.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import timedelta
from pathlib import Path
from typing import Any

from stanchion.errors import ConfigError, ShipError
from stanchion.jsonstore import JsonStore
from stanchion.ship.backoff import BackoffPolicy
from stanchion.ship.batch import BatchLimits
from stanchion.ship.meter import DEFAULT_FLOORS, Meter
from stanchion.timestamps import NanoTime
from stanchion.units import parse_bytes, parse_duration

METER_FILENAME = "meter.json"
FORMAT_VERSION = 1

DEFAULT_ENDPOINT = "https://collector.invalid/v1/ingest"
DEFAULT_ALLOWANCE = 2 * 1000 * 1000 * 1000
DEFAULT_INTERVAL = timedelta(minutes=5)


@dataclass(frozen=True, slots=True)
class ShipSettings:
    """Everything the shipper needs from the config file."""

    endpoint: str = DEFAULT_ENDPOINT
    token: str = ""
    monthly_allowance_bytes: int = DEFAULT_ALLOWANCE
    anchor_day: int = 1
    batch_bytes: int = 256 * 1024
    batch_records: int = 2000
    attempt_timeout: timedelta = timedelta(seconds=30)
    interval: timedelta = DEFAULT_INTERVAL
    floors: dict[str, float] = field(default_factory=lambda: dict(DEFAULT_FLOORS))
    delete_acknowledged: bool = True

    def __post_init__(self) -> None:
        if not self.endpoint.startswith(("http://", "https://", "file://")):
            raise ConfigError("endpoint must be an http or file url", detail=self.endpoint)
        if self.attempt_timeout <= timedelta(0):
            raise ConfigError("attempt_timeout must be positive")
        if self.interval <= timedelta(0):
            raise ConfigError("ship interval must be positive")
        if self.batch_bytes > self.monthly_allowance_bytes:
            raise ConfigError("a single batch cannot exceed the monthly allowance")
        try:
            self.meter()
        except ShipError as exc:
            raise ConfigError(exc.message, detail=exc.detail) from exc

    @property
    def to_file(self) -> bool:
        return self.endpoint.startswith("file://")

    @property
    def file_directory(self) -> Path:
        if not self.to_file:
            raise ConfigError("this endpoint is not a directory", detail=self.endpoint)
        return Path(self.endpoint.removeprefix("file://"))

    def limits(self) -> BatchLimits:
        return BatchLimits(max_bytes=self.batch_bytes, max_records=self.batch_records)

    def meter(self) -> Meter:
        return Meter(
            allowance_bytes=self.monthly_allowance_bytes,
            anchor_day=self.anchor_day,
            floors=dict(self.floors),
        )

    def backoff_policy(self) -> BackoffPolicy:
        return BackoffPolicy()


def ship_settings_from_mapping(raw: dict[str, Any]) -> ShipSettings:
    """Build the settings from the config block."""
    known = {
        "endpoint",
        "token",
        "monthly_allowance",
        "anchor_day",
        "batch",
        "batch_records",
        "attempt_timeout",
        "interval",
        "floors",
        "delete_acknowledged",
    }
    unknown = set(raw) - known
    if unknown:
        raise ConfigError("unknown ship key", detail=", ".join(sorted(unknown)))
    floors_raw = raw.get("floors", DEFAULT_FLOORS)
    if not isinstance(floors_raw, dict):
        raise ConfigError("ship floors must be a mapping")
    return ShipSettings(
        endpoint=str(raw.get("endpoint", DEFAULT_ENDPOINT)),
        token=str(raw.get("token", "")),
        monthly_allowance_bytes=parse_bytes(raw.get("monthly_allowance", DEFAULT_ALLOWANCE)),
        anchor_day=int(raw.get("anchor_day", 1)),
        batch_bytes=parse_bytes(raw.get("batch", 256 * 1024)),
        batch_records=int(raw.get("batch_records", 2000)),
        attempt_timeout=parse_duration(raw.get("attempt_timeout", 30)),
        interval=parse_duration(raw.get("interval", 300)),
        floors={str(name): float(value) for name, value in floors_raw.items()},
        delete_acknowledged=bool(raw.get("delete_acknowledged", True)),
    )


@dataclass(slots=True)
class MeterStore:
    """The meter's state on disk."""

    directory: Path
    store: JsonStore = field(init=False)

    def __post_init__(self) -> None:
        self.directory = Path(self.directory)
        self.store = JsonStore(
            path=self.directory / METER_FILENAME, version=FORMAT_VERSION, indent=1
        )

    @property
    def path(self) -> Path:
        return self.store.path

    @property
    def corrupt(self) -> int:
        return self.store.corrupt

    def load(self, settings: ShipSettings) -> Meter:
        """Read the meter, or start a fresh one if there is nothing usable.

        The allowance always comes from the config, never from the file. A site
        whose plan was changed must use the new number immediately, and the file
        is only there to remember what has been spent.
        """
        fresh = settings.meter()
        payload = self.store.load()
        if payload is None:
            return fresh
        try:
            fresh.used_bytes = int(payload["used_bytes"])
            fresh.started_at = NanoTime.parse(str(payload["started_at"]))
            fresh.rollovers = int(payload.get("rollovers", 0))
        except (KeyError, TypeError, ValueError):
            self.store.corrupt += 1
            return settings.meter()
        return fresh

    def save(self, meter: Meter) -> Path:
        return self.store.save(
            {
                "used_bytes": meter.used_bytes,
                "started_at": meter.started_at.isoformat(),
                "rollovers": meter.rollovers,
                "allowance_bytes": meter.allowance_bytes,
            }
        )
