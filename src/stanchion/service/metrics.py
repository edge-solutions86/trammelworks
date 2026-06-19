"""The site's numbers, gathered from disk rather than from a running process.

The service is a separate process from the collector. It cannot read the
collector's counters, and adding a channel between them would mean the query
service could stall the thing that ships records. So everything here is derived
from what is on disk, which is the same evidence an engineer would look at:
segments in the spool directory, rows in the archive, watermarks in the state
file.

That makes these gauges rather than counters. "How many records has this site
ever read" is not answerable from disk and is not asked here; "how much of the
spool budget is in use right now" is, and it is the question that matters when
a site stops shipping.

The text form is deliberately plain. One ``name value`` pair per line, no types,
no labels, no help text. The estate poller that reads it runs on a box older
than this software and parses with awk, and a format it cannot read is a format
that does not get read.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from stanchion.archive.repository import Repository
from stanchion.archive.schema import open_archive
from stanchion.archive.space import measure as measure_archive
from stanchion.config import SiteConfig
from stanchion.errors import StanchionError
from stanchion.ship.ack import AckStore
from stanchion.ship.config import MeterStore
from stanchion.spool.budget import measure as measure_spool
from stanchion.spool.segment import list_segments

CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"


@dataclass(slots=True)
class Gauges:
    """Everything one look at a site's disk can tell you."""

    values: dict[str, int] = field(default_factory=dict)
    missing: list[str] = field(default_factory=list)

    def set(self, name: str, value: int) -> None:
        self.values[name] = value

    def note_missing(self, area: str) -> None:
        """Record that an area could not be read, without failing the whole read.

        A site whose archive is being vacuumed cannot answer for it, and a
        metrics endpoint that returns nothing at all in that moment is one that
        looks like the site is down.
        """
        self.missing.append(area)

    def render(self) -> str:
        """One ``name value`` pair per line, sorted."""
        lines = [f"{name} {value}" for name, value in sorted(self.values.items())]
        return "\n".join(lines) + "\n" if lines else ""

    def as_dict(self) -> dict[str, int]:
        return dict(sorted(self.values.items()))


def spool_gauges(config: SiteConfig, into: Gauges) -> None:
    """What the spool directory says about itself."""
    budget = config.spool.budget()
    segments = list_segments(config.spool.path)
    usage = measure_spool(segments)
    into.set("spool_limit_bytes", budget.limit_bytes)
    into.set("spool_usable_bytes", budget.usable_bytes)
    into.set("spool_used_bytes", usage.used)
    into.set("spool_free_bytes", max(budget.limit_bytes - usage.used, 0))
    into.set("spool_segments", usage.segments)
    into.set("spool_full_percent", int(budget.fill_ratio(usage) * 100))
    for klass in config.spool.table().ship_order():
        into.set(f"spool_class_bytes_{klass}", usage.by_class.get(klass, 0))
        into.set(
            f"spool_class_segments_{klass}",
            sum(1 for item in segments if item.klass == klass),
        )


def ship_gauges(config: SiteConfig, state: Path, into: Gauges) -> None:
    """What the meter and the watermarks say."""
    meter = MeterStore(directory=state).load(config.ship)
    into.set("ship_allowance_bytes", meter.allowance_bytes)
    into.set("ship_used_bytes", meter.used_bytes)
    into.set("ship_remaining_bytes", meter.remaining)
    into.set("ship_allowance_percent", int(meter.spent_ratio * 100))
    ledger = AckStore(directory=state).load()
    into.set("ship_outstanding_records", ledger.outstanding)
    into.set("ship_pending_segments", len(ledger.pending_segments()))


def archive_gauges(config: SiteConfig, into: Gauges) -> None:
    """What the archive file says, when there is one."""
    settings = config.archive
    if not settings.enabled:
        into.set("archive_enabled", 0)
        return
    into.set("archive_enabled", 1)
    if not settings.file.exists():
        into.note_missing("archive")
        return
    try:
        connection = open_archive(settings.file, read_only=True)
        into.set("archive_records", Repository(connection=connection).count())
        footprint = measure_archive(connection, settings.file)
    except StanchionError:
        into.note_missing("archive")
        return
    into.set("archive_limit_bytes", settings.max_bytes)
    into.set("archive_bytes", footprint.total)
    into.set("archive_used_bytes", footprint.used)
    into.set("archive_reclaimable_bytes", footprint.reclaimable)


def gather(config: SiteConfig, state: Path) -> Gauges:
    """Every gauge this site can answer for right now.

    Each area is read on its own and a failure in one does not stop the others.
    The point of this endpoint is the moment something is wrong, which is
    exactly when one of these reads is most likely to fail.
    """
    found = Gauges()
    try:
        spool_gauges(config, found)
    except (StanchionError, OSError):
        found.note_missing("spool")
    try:
        ship_gauges(config, state, found)
    except (StanchionError, OSError):
        found.note_missing("ship")
    try:
        archive_gauges(config, found)
    except (StanchionError, OSError):
        found.note_missing("archive")
    found.set("site_areas_unreadable", len(found.missing))
    return found
