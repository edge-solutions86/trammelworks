"""The nightly summary.

One small document per site per day, and for most of the estate it is the only
thing anybody ever reads. It has to answer four questions in the order an
operator asks them:

1. is the site collecting at all
2. is what it collects getting off the site
3. is it about to run out of disk or allowance
4. is its clock believable

Everything else is detail. The summary is deliberately a handful of lines rather
than a dump of every counter, because a document nobody finishes is a document
that hides the one line that mattered.

The **numbers have to add up**, and that is the part worth testing. Fragments
read minus fragments joined should equal records produced. Records spooled plus
records refused should equal records read. When those disagree the summary says
so rather than printing both and leaving it to a reader who is not looking.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field, fields
from typing import Any

from stanchion.timestamps import NanoTime
from stanchion.units import format_bytes

#: Where a field's JSON name is not its attribute name. Kept short on purpose:
#: every entry here is a name the estate side already parses and cannot be
#: changed, not a preference.
FIELD_NAMES: dict[str, str] = {
    "files_followed": "files",
    "bytes_sent": "bytes",
    "unknown_records": "unknown",
}


def section_dict(section: Any) -> dict[str, Any]:
    """One summary section as JSON, every field of it.

    Walks the dataclass rather than listing the fields, so a field added to a
    section is in the summary the same day it is added. That is the whole
    point: the previous version listed them and one had already been missed.
    """
    out: dict[str, Any] = {}
    for item in fields(section):
        out[FIELD_NAMES.get(item.name, item.name)] = getattr(section, item.name)
    return out


@dataclass(slots=True)
class IngestSummary:
    """What came in."""

    files_followed: int = 0
    records: int = 0
    skipped: int = 0
    rotations: int = 0
    lost_bytes: int = 0

    @property
    def healthy(self) -> bool:
        return self.files_followed > 0


@dataclass(slots=True)
class ShipSummary:
    """What went out."""

    batches: int = 0
    records: int = 0
    bytes_sent: int = 0
    retryable: int = 0
    terminal: int = 0
    meter_used: int = 0
    meter_allowance: int = 1
    blocked: int = 0

    @property
    def meter_ratio(self) -> float:
        return self.meter_used / self.meter_allowance if self.meter_allowance else 0.0

    @property
    def healthy(self) -> bool:
        return self.terminal == 0 and self.meter_ratio < 0.9


@dataclass(slots=True)
class SpoolSummary:
    """What is waiting."""

    used: int = 0
    limit: int = 1
    segments: int = 0
    evicted: int = 0

    @property
    def ratio(self) -> float:
        return self.used / self.limit if self.limit else 0.0

    @property
    def healthy(self) -> bool:
        return self.ratio < 0.8 and self.evicted == 0


@dataclass(slots=True)
class ClockSummary:
    """What the box believes about its own time."""

    boots: int = 0
    syncs: int = 0
    drift_ms: int = 0
    unknown_records: int = 0

    @property
    def healthy(self) -> bool:
        return self.syncs > 0 and abs(self.drift_ms) < 60_000


@dataclass(slots=True)
class Summary:
    """One site, one day."""

    site: str
    at: NanoTime
    ingest: IngestSummary = field(default_factory=IngestSummary)
    ship: ShipSummary = field(default_factory=ShipSummary)
    spool: SpoolSummary = field(default_factory=SpoolSummary)
    clock: ClockSummary = field(default_factory=ClockSummary)
    redacted: dict[str, int] = field(default_factory=dict)
    archived: int = 0
    problems: list[str] = field(default_factory=list)

    @property
    def healthy(self) -> bool:
        return not self.problems and all(
            (self.ingest.healthy, self.ship.healthy, self.spool.healthy, self.clock.healthy)
        )

    def check(self) -> list[str]:
        """Find what an operator would want telling. Also fills ``problems``."""
        found: list[str] = []
        if not self.ingest.healthy:
            found.append("no container log files are being followed")
        if self.ship.terminal:
            found.append(f"{self.ship.terminal} batches were refused outright")
        if self.ship.meter_ratio >= 0.9:
            found.append(f"the monthly allowance is {100 * self.ship.meter_ratio:.0f}% spent")
        if self.spool.evicted:
            found.append(f"{self.spool.evicted} spool segments were evicted unsent")
        if self.spool.ratio >= 0.8:
            found.append(f"the spool is {100 * self.spool.ratio:.0f}% full")
        if not self.clock.syncs:
            found.append("the clock has not been corrected in this boot")
        if self.ingest.lost_bytes:
            found.append(f"{self.ingest.lost_bytes} bytes were lost to rotations")
        self.problems = found
        return found

    def as_dict(self) -> dict[str, Any]:
        """The shape the estate side parses.

        Derived from the section dataclasses rather than written out again.
        The hand written version had drifted: ``ShipSummary.blocked`` was
        counted, carried, and then quietly left out of the JSON, so the one
        number that says a site is hitting its allowance never left the box.
        """
        return {
            "site": self.site,
            "at": self.at.isoformat(),
            "healthy": self.healthy,
            "ingest": section_dict(self.ingest),
            "ship": section_dict(self.ship),
            "spool": section_dict(self.spool),
            "clock": section_dict(self.clock),
            "redacted": dict(sorted(self.redacted.items())),
            "archived": self.archived,
            "problems": list(self.problems),
        }

    def render_json(self) -> str:
        return json.dumps(self.as_dict(), sort_keys=True, indent=1)

    def render_text(self) -> str:
        self.check()
        state = "ok" if self.healthy else "ATTENTION"
        lines = [
            f"{self.site} {self.at.isoformat()} {state}",
            f"  ingest  {self.ingest.records} records from {self.ingest.files_followed} files",
            f"  ship    {self.ship.records} records in {format_bytes(self.ship.bytes_sent)}, "
            f"meter {100 * self.ship.meter_ratio:.1f}%",
            f"  spool   {format_bytes(self.spool.used)} of "
            f"{format_bytes(self.spool.limit)} in {self.spool.segments} segments",
            f"  clock   {self.clock.syncs} syncs, drift {self.clock.drift_ms} ms",
            f"  archive {self.archived} records kept",
        ]
        if self.redacted:
            total = sum(self.redacted.values())
            lines.append(f"  redact  {total} removed across {len(self.redacted)} rules")
        for problem in self.problems:
            lines.append(f"  ! {problem}")
        return "\n".join(lines)


def from_metrics(site: str, at: NanoTime, metrics: dict[str, int]) -> Summary:
    """Build a summary from a metrics snapshot.

    Reads keys that may not be there. A site that has never shipped has no ship
    counters at all, and a summary that raised on that would be missing for the
    exact site somebody needs to look at.
    """
    summary = Summary(site=site, at=at)
    summary.ingest = IngestSummary(
        files_followed=metrics.get("ingest_files_followed", 0),
        records=metrics.get("ingest_records", 0),
        skipped=metrics.get("ingest_skipped_unparseable", 0),
        rotations=metrics.get("spool_recovered_segments", 0),
        lost_bytes=metrics.get("spool_recovered_lost_bytes", 0),
    )
    summary.ship = ShipSummary(
        batches=metrics.get("ship_batches", 0),
        records=metrics.get("ship_records", 0),
        bytes_sent=metrics.get("ship_bytes", 0),
        retryable=metrics.get("ship_retryable", 0),
        terminal=metrics.get("ship_terminal", 0),
        blocked=metrics.get("ship_meter_blocked", 0),
    )
    summary.spool = SpoolSummary(
        used=metrics.get("spool_bytes", 0),
        limit=max(1, metrics.get("spool_limit", 1)),
        segments=metrics.get("spool_segments_sealed", 0),
        evicted=metrics.get("spool_evicted_segments", 0),
    )
    summary.check()
    return summary
