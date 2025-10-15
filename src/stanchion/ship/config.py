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

import json
import os
import tempfile
from dataclasses import dataclass, field
from datetime import timedelta
from pathlib import Path
from typing import Any

from stanchion.errors import ConfigError, ShipError
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
    corrupt: int = 0

    def __post_init__(self) -> None:
        self.directory = Path(self.directory)

    @property
    def path(self) -> Path:
        return self.directory / METER_FILENAME

    def load(self, settings: ShipSettings) -> Meter:
        """Read the meter, or start a fresh one if there is nothing usable.

        The allowance always comes from the config, never from the file. A site
        whose plan was changed must use the new number immediately, and the file
        is only there to remember what has been spent.
        """
        fresh = settings.meter()
        try:
            text = self.path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return fresh
        except OSError as exc:
            raise ShipError("meter state unreadable", detail=str(exc)) from exc
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            self.corrupt += 1
            return fresh
        if not isinstance(payload, dict) or payload.get("version") != FORMAT_VERSION:
            self.corrupt += 1
            return fresh
        try:
            fresh.used_bytes = int(payload["used_bytes"])
            fresh.started_at = NanoTime.parse(str(payload["started_at"]))
            fresh.rollovers = int(payload.get("rollovers", 0))
        except (KeyError, TypeError, ValueError):
            self.corrupt += 1
            return settings.meter()
        return fresh

    def save(self, meter: Meter) -> Path:
        self.directory.mkdir(parents=True, exist_ok=True)
        payload = {
            "version": FORMAT_VERSION,
            "used_bytes": meter.used_bytes,
            "started_at": meter.started_at.isoformat(),
            "rollovers": meter.rollovers,
            "allowance_bytes": meter.allowance_bytes,
        }
        handle, temporary = tempfile.mkstemp(dir=self.directory, suffix=".tmp")
        try:
            with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as stream:
                json.dump(payload, stream, sort_keys=True, indent=1)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        except BaseException:
            Path(temporary).unlink(missing_ok=True)
            raise
        return self.path
