"""Everything the archive needs doing to it that is not writing a record.

This lived on ``Site`` until it did not fit there any more. The site object is
about the path a log line takes from a container to the collector, and none of
this is on that path: it runs on a timer, it touches only the archive, and the
worst thing it can do is delete something. Three good reasons to be able to read
it without the ingest pipeline in the same file.

The order is the whole design and it is not negotiable.

1. **Sweep.** Retention decides what may go. Anything it takes is free, in the
   sense that nobody agreed to keep it past its window.
2. **Trim.** Only if the cap says there is no room, and only past retention.
   This deletes records somebody would have kept, so it goes second: trimming
   first would take records the sweep was about to remove anyway, at the cost
   of deleting something that mattered instead.
3. **Vacuum.** Last, because it is the only step that hands space back to the
   card, and doing it before the deletions would rewrite the whole file and
   then immediately punch holes in it.

Every step reports rather than raises. A site whose card is failing must still
collect, and a housekeeping pass that stopped the box would turn a disk problem
into an outage.
"""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field
from pathlib import Path

from stanchion.archive.config import ArchiveSettings
from stanchion.archive.space import (
    Footprint,
    Trimmer,
    TrimReport,
    measure,
    vacuum,
    worth_vacuuming,
)
from stanchion.archive.sweeper import Sweeper, SweepReport
from stanchion.clocks import Clock, SystemClock
from stanchion.errors import RetentionError
from stanchion.eventlog import EventLog, null_log
from stanchion.spool.priority import PriorityTable
from stanchion.telemetry import Registry


@dataclass(slots=True)
class HousekeepReport:
    """What one pass over the archive's disk did.

    Three stages in one report because they only make sense together: the sweep
    decides what may go, the trim takes more when the cap says it has to, and
    the vacuum is the only one of the three that hands anything back to the
    card. Reporting them apart would let an operator read a large sweep as a
    large saving.
    """

    sweep: SweepReport = field(default_factory=SweepReport)
    trim: TrimReport = field(default_factory=TrimReport)
    reclaimed: int = 0
    before: Footprint = field(default_factory=Footprint)
    after: Footprint = field(default_factory=Footprint)

    @property
    def idle(self) -> bool:
        return self.sweep.deleted == 0 and self.trim.deleted == 0 and self.reclaimed == 0

    @property
    def deleted(self) -> int:
        """Records removed by either step, which is what an operator asks."""
        return self.sweep.deleted + self.trim.deleted

    def __str__(self) -> str:
        return (
            f"swept {self.sweep.deleted}, trimmed {self.trim.deleted}, freed {self.reclaimed} bytes"
        )


@dataclass(slots=True)
class Housekeeper:
    """Runs the three steps against one site's archive."""

    connection: sqlite3.Connection
    settings: ArchiveSettings
    sweeper: Sweeper
    table: PriorityTable = field(default_factory=PriorityTable)
    clock: Clock = field(default_factory=SystemClock)
    events: EventLog = field(default_factory=null_log)
    metrics: Registry = field(default_factory=Registry)

    @property
    def path(self) -> Path:
        return self.settings.file

    def footprint(self) -> Footprint:
        return measure(self.connection, self.path)

    def run(self) -> HousekeepReport:
        """Retention, then the cap, then give the card its space back."""
        report = HousekeepReport()
        report.before = self.footprint()
        report.sweep = self.sweep()
        report.trim = self.trim()
        if self.settings.vacuum:
            report.reclaimed = self.vacuum()
        report.after = self.footprint()
        return report

    def sweep(self) -> SweepReport:
        """Apply the retention policy."""
        report = self.sweeper.sweep(self.clock.now())
        if report.deleted:
            self.metrics.incr("archive_deleted", report.deleted)
            self.events.info("archive_swept", deleted=report.deleted, held=report.held)
        return report

    def trim(self) -> TrimReport:
        """Take records retention kept, when the cap leaves no choice."""
        if not self.settings.trim_to_cap:
            return TrimReport()
        trimmer = Trimmer(
            connection=self.connection,
            path=self.path,
            cap_bytes=self.settings.max_bytes,
            table=self.table,
        )
        report = trimmer.run(self.sweeper.holds.all(), self.clock.now())
        if report.deleted:
            self.metrics.incr("archive_trimmed", report.deleted)
            self.events.warn(
                "archive_trimmed",
                deleted=report.deleted,
                held=report.held,
                stopped=report.stopped,
            )
        return report

    def vacuum(self) -> int:
        """Rewrite the archive if the free space is worth the write cycles."""
        if not worth_vacuuming(
            self.footprint(), min_reclaim=self.settings.vacuum_min_reclaim_bytes
        ):
            return 0
        try:
            freed = vacuum(self.connection, self.path)
        except RetentionError as exc:
            self.metrics.incr("archive_vacuum_failed")
            self.events.exception("archive_vacuum_failed", exc)
            return 0
        self.metrics.incr("archive_vacuumed")
        self.metrics.incr("archive_vacuum_freed", freed)
        self.events.info("archive_vacuumed", freed=freed)
        return freed
