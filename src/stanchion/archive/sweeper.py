"""Deleting what retention says may go, and keeping the holds that say it may not.

The sweep is deliberately dull and deliberately bounded. It deletes in batches
with a cap per pass, because a site that has been off for a month and comes back
with four million expired rows must not spend forty minutes holding a write lock
while the collector is trying to write into the same file.

Holds live in the archive rather than in the config file. A hold is placed by
the estate in the middle of the night, has to take effect without a restart, and
has to be visible to anyone who opens the database afterwards and asks why a
year of records is still there. A config file satisfies none of that.

Nothing here decides anything. Every question about whether a record may go is
put to the retention module, which is the only place that knows about the scheme
floors.
"""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field

from stanchion.archive.repository import ORDER_COLUMN
from stanchion.archive.retention import Hold, RetentionPolicy, decide
from stanchion.errors import RetentionError
from stanchion.timestamps import NanoTime

DEFAULT_MAX_DELETES = 50_000


@dataclass(slots=True)
class HoldStore:
    """The legal holds recorded in the archive."""

    connection: sqlite3.Connection

    def place(self, hold: Hold) -> None:
        """Record a hold, replacing one of the same name."""
        with self.connection:
            self.connection.execute(
                "INSERT OR REPLACE INTO holds (name, reason, placed_ns, from_ns, until_ns)"
                " VALUES (?, ?, ?, ?, ?)",
                (
                    hold.name,
                    hold.reason,
                    hold.placed_at.epoch_ns,
                    None if hold.covers_from is None else hold.covers_from.epoch_ns,
                    None if hold.covers_until is None else hold.covers_until.epoch_ns,
                ),
            )

    def lift(self, name: str) -> bool:
        """Remove a hold. Returns whether there was one."""
        with self.connection:
            cursor = self.connection.execute("DELETE FROM holds WHERE name = ?", (name,))
        return cursor.rowcount > 0

    def all(self) -> list[Hold]:
        rows = self.connection.execute("SELECT * FROM holds ORDER BY placed_ns, name")
        return [
            Hold(
                name=row["name"],
                reason=row["reason"],
                placed_at=NanoTime(int(row["placed_ns"])),
                covers_from=None if row["from_ns"] is None else NanoTime(int(row["from_ns"])),
                covers_until=None if row["until_ns"] is None else NanoTime(int(row["until_ns"])),
            )
            for row in rows
        ]

    def get(self, name: str) -> Hold | None:
        for hold in self.all():
            if hold.name == name:
                return hold
        return None


@dataclass(slots=True)
class SweepReport:
    """What one sweep did, and what it left."""

    examined: int = 0
    deleted: int = 0
    held: int = 0
    by_class: dict[str, int] = field(default_factory=dict)
    capped: bool = False

    def note(self, klass: str) -> None:
        self.by_class[klass] = self.by_class.get(klass, 0) + 1

    def __str__(self) -> str:
        end = ", capped" if self.capped else ""
        return f"swept {self.examined} rows, deleted {self.deleted}, held {self.held}{end}"


@dataclass(slots=True)
class Sweeper:
    """Applies the retention policy to the archive."""

    connection: sqlite3.Connection
    policy: RetentionPolicy = field(default_factory=RetentionPolicy)
    max_deletes: int = DEFAULT_MAX_DELETES

    def __post_init__(self) -> None:
        if self.max_deletes < 1:
            raise RetentionError("max_deletes must be at least one")

    @property
    def holds(self) -> HoldStore:
        return HoldStore(connection=self.connection)

    def candidates(self, now: NanoTime, limit: int) -> list[sqlite3.Row]:
        """Rows old enough to be worth asking about.

        Bounded by the SHORTEST window in the policy, not the longest. A record
        is deletable once it is older than its own class's window, so the
        earliest anything can qualify is one shortest window ago. Using the
        longest would skip every class that expires sooner, which is every class
        that actually produces deletions.
        """
        shortest = min(
            (self.policy.window_for(klass) for klass in self.policy.windows),
            default=None,
        )
        if shortest is None:
            return []
        horizon = now.shifted(-int(shortest.total_seconds() * 1_000_000_000))
        return list(
            self.connection.execute(
                f"SELECT id, klass, {ORDER_COLUMN} AS at FROM records"
                f" WHERE {ORDER_COLUMN} < ? ORDER BY at ASC LIMIT ?",
                (horizon.epoch_ns, limit),
            )
        )

    def sweep(self, now: NanoTime) -> SweepReport:
        """One bounded pass."""
        report = SweepReport()
        holds = self.holds.all()
        rows = self.candidates(now, self.max_deletes)
        doomed: list[int] = []
        for row in rows:
            report.examined += 1
            found = decide(self.policy, holds, row["klass"], NanoTime(int(row["at"])), now)
            if found.deletable:
                doomed.append(int(row["id"]))
                report.note(row["klass"])
            else:
                report.held += 1
        if doomed:
            with self.connection:
                self.connection.executemany(
                    "DELETE FROM records WHERE id = ?", [(item,) for item in doomed]
                )
            report.deleted = len(doomed)
        report.capped = len(rows) >= self.max_deletes
        return report

    def sweep_until_clear(self, now: NanoTime, max_passes: int = 20) -> SweepReport:
        """Keep sweeping while a pass is still hitting its cap."""
        total = SweepReport()
        for _ in range(max_passes):
            pass_report = self.sweep(now)
            total.examined += pass_report.examined
            total.deleted += pass_report.deleted
            total.held += pass_report.held
            for klass, count in pass_report.by_class.items():
                total.by_class[klass] = total.by_class.get(klass, 0) + count
            if not pass_report.capped or pass_report.deleted == 0:
                break
            total.capped = True
        return total
