"""Turning the spool section of a site config into working parts.

The section is written in the units a commissioning engineer thinks in, and the
checks here are the ones that catch a config which is internally consistent but
cannot work:

* a segment larger than the usable budget, which would mean the first write
  after a roll can never fit
* a reserve so large that nothing is usable
* a unit assigned to a class nobody defined

All three read fine on their own. They only fail against each other, which is
why they are checked where the pieces meet rather than in each piece.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from stanchion.errors import ConfigError, SpoolError
from stanchion.sections import check_keys, section
from stanchion.spool.budget import Budget
from stanchion.spool.priority import PriorityTable, table_from_mapping
from stanchion.spool.segment import DEFAULT_SEGMENT_BYTES
from stanchion.units import format_bytes, parse_bytes

DEFAULT_DIRECTORY = "/var/lib/stanchion/spool"
DEFAULT_BUDGET = 512 * 1024 * 1024
DEFAULT_RESERVE = 0.1
DEFAULT_SYNC_EVERY = 0


@dataclass(frozen=True, slots=True)
class SpoolSettings:
    """Everything the spool needs, already checked against itself."""

    directory: str = DEFAULT_DIRECTORY
    budget_bytes: int = DEFAULT_BUDGET
    segment_bytes: int = DEFAULT_SEGMENT_BYTES
    reserve_ratio: float = DEFAULT_RESERVE
    sync_every: int = DEFAULT_SYNC_EVERY
    compress: bool = True
    priority: PriorityTable | None = None

    def __post_init__(self) -> None:
        if self.sync_every < 0:
            raise ConfigError("sync_every cannot be negative", detail=str(self.sync_every))
        try:
            budget = self.budget()
        except SpoolError as exc:
            raise ConfigError(exc.message, detail=exc.detail) from exc
        if self.segment_bytes <= 0:
            raise ConfigError("segment size must be positive", detail=str(self.segment_bytes))
        if self.segment_bytes > budget.usable_bytes:
            raise ConfigError(
                "the segment size does not fit inside the usable budget",
                detail=(
                    f"segment {format_bytes(self.segment_bytes)} "
                    f"> usable {format_bytes(budget.usable_bytes)}"
                ),
            )

    def budget(self) -> Budget:
        return Budget(limit_bytes=self.budget_bytes, reserve_ratio=self.reserve_ratio)

    def table(self) -> PriorityTable:
        return self.priority if self.priority is not None else PriorityTable()

    @property
    def path(self) -> Path:
        return Path(self.directory)

    def describe(self) -> str:
        budget = self.budget()
        return (
            f"spool {self.directory} "
            f"budget {format_bytes(self.budget_bytes)} "
            f"usable {format_bytes(budget.usable_bytes)} "
            f"segments {format_bytes(self.segment_bytes)}"
        )


def spool_settings_from_mapping(raw: dict[str, Any]) -> SpoolSettings:
    """Build the settings from the config block."""
    check_keys(
        raw,
        {
            "directory",
            "budget",
            "segment",
            "reserve_ratio",
            "sync_every",
            "compress",
            "priority",
        },
        "spool",
    )
    priority_raw = None if raw.get("priority") is None else section(raw, "priority", "spool")
    return SpoolSettings(
        directory=str(raw.get("directory", DEFAULT_DIRECTORY)),
        budget_bytes=parse_bytes(raw.get("budget", DEFAULT_BUDGET)),
        segment_bytes=parse_bytes(raw.get("segment", DEFAULT_SEGMENT_BYTES)),
        reserve_ratio=float(raw.get("reserve_ratio", DEFAULT_RESERVE)),
        sync_every=int(raw.get("sync_every", DEFAULT_SYNC_EVERY)),
        compress=bool(raw.get("compress", True)),
        priority=None if priority_raw is None else table_from_mapping(priority_raw),
    )
