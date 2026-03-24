"""Keeping the archive inside the space the card actually has.

Retention answers "may this go". It does not answer "is there room", and on a
controller with an eight gigabyte industrial SD card those are different
questions with different answers. A site that has been shipping fine for a year
is inside every retention window it has and still filling the card.

Two mechanisms, and they are separate on purpose.

**Vacuum** gives back space that is already free. Deleting rows in SQLite does
not shrink the file, it puts pages on a freelist for the next insert to use, so
after the retention sweep has taken four million rows out the file is the same
size it was. That is the right default: the pages get reused and rewriting a
multi gigabyte file on an SD card costs write cycles the card is rated for a
finite number of. It is only worth doing when the free portion is large enough
that leaving it costs more than the rewrite.

**Trim** takes records that retention would have kept. It is the unpleasant one
and it is last, which is why it goes by class and oldest first: price sign
chatter from March goes before anything from the payment kernel, and a legal
hold is never trimmed at all. A site that hits its cap with nothing but held
payment records left stops trimming and says so, because the alternative is
deleting the one thing the archive exists for.

The footprint reports the write ahead log as well, because an operator looking
at what is left on the card needs it. The cap is not applied to that number
though, it is applied to the live pages, and the distinction is what makes the
trim loop terminate. Deleting a row does not shrink the file and it grows the
log, so a loop watching the total would delete everything it was allowed to and
still be over. Only a checkpoint returns the log and only a vacuum returns the
free pages; trimming returns neither, so trimming is measured against the one
number it does move.
"""

from __future__ import annotations

import shutil
import sqlite3
from dataclasses import dataclass, field
from pathlib import Path

from stanchion.archive.repository import ORDER_COLUMN
from stanchion.archive.retention import Hold
from stanchion.errors import RetentionError
from stanchion.spool.priority import PriorityTable
from stanchion.timestamps import NanoTime

WAL_SUFFIX = "-wal"
SHM_SUFFIX = "-shm"

#: Vacuum only once the freelist is worth more than this.
DEFAULT_MIN_RECLAIM = 32 * 1024 * 1024

#: Or once it is this much of the file, whichever comes first.
DEFAULT_MIN_FRACTION = 0.2

DEFAULT_TRIM_BATCH = 5000


@dataclass(frozen=True, slots=True)
class Footprint:
    """What the archive is taking up right now."""

    main_bytes: int = 0
    wal_bytes: int = 0
    page_size: int = 0
    page_count: int = 0
    free_pages: int = 0

    @property
    def total(self) -> int:
        """What the card has given up, free pages and all."""
        return self.main_bytes + self.wal_bytes

    @property
    def used(self) -> int:
        """What the records in it actually occupy.

        Live pages only. Not the file size, which deleting does not move, and
        not the log, which deleting makes larger. This is the number a trim is
        measured against because it is the only one a trim can bring down.
        """
        return max(self.page_count - self.free_pages, 0) * self.page_size

    @property
    def reclaimable(self) -> int:
        """What a vacuum would give back, near enough."""
        return self.free_pages * self.page_size

    @property
    def free_fraction(self) -> float:
        return self.reclaimable / self.main_bytes if self.main_bytes else 0.0

    def __str__(self) -> str:
        return (
            f"{self.total} bytes, {self.reclaimable} reclaimable "
            f"({self.free_pages} of {self.page_count} pages free)"
        )


def file_size(path: Path) -> int:
    try:
        return path.stat().st_size
    except OSError:
        return 0


def measure(connection: sqlite3.Connection, path: Path) -> Footprint:
    """Read the footprint from the file and from the pragmas."""
    try:
        page_size = int(connection.execute("PRAGMA page_size").fetchone()[0])
        page_count = int(connection.execute("PRAGMA page_count").fetchone()[0])
        free_pages = int(connection.execute("PRAGMA freelist_count").fetchone()[0])
    except sqlite3.Error as exc:
        raise RetentionError("archive will not report its size", detail=str(exc)) from exc
    return Footprint(
        main_bytes=file_size(path),
        wal_bytes=file_size(path.with_name(path.name + WAL_SUFFIX)),
        page_size=page_size,
        page_count=page_count,
        free_pages=free_pages,
    )


def worth_vacuuming(
    footprint: Footprint,
    min_reclaim: int = DEFAULT_MIN_RECLAIM,
    min_fraction: float = DEFAULT_MIN_FRACTION,
) -> bool:
    """Whether the free space is worth the rewrite it costs to get back."""
    if footprint.reclaimable <= 0:
        return False
    return footprint.reclaimable >= min_reclaim or footprint.free_fraction >= min_fraction


def checkpoint(connection: sqlite3.Connection) -> None:
    """Fold the write ahead log back into the database and truncate it."""
    try:
        connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    except sqlite3.Error as exc:
        raise RetentionError("archive will not checkpoint", detail=str(exc)) from exc


def vacuum(connection: sqlite3.Connection, path: Path) -> int:
    """Rewrite the archive without its free pages. Returns the bytes given back.

    There is a checkpoint on both sides of it and both are load bearing. The
    one before folds in pages the vacuum would otherwise not see. The one after
    matters more: in write ahead log mode the rebuilt database lands in the log
    first, so without it the file on disk is exactly the size it was and this
    reports having freed nothing.

    SQLite builds the new file beside the old one and swaps, so this needs as
    much free space again as the archive currently takes. Checking first is not
    paranoia: the situation that produces a vacuum is a card that is filling up.
    """
    try:
        connection.commit()
        connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        before = file_size(path)
        if not room_to_vacuum(path, before):
            raise RetentionError(
                "not enough free space to vacuum the archive",
                detail=f"{before} bytes needed",
            )
        connection.execute("VACUUM")
        connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    except sqlite3.Error as exc:
        raise RetentionError("vacuum failed", detail=str(exc)) from exc
    return max(before - file_size(path), 0)


def room_to_vacuum(path: Path, needed: int) -> bool:
    """Whether the filesystem has room for the copy a vacuum makes."""
    try:
        free = shutil.disk_usage(path.parent).free
    except OSError:
        # No answer is not a no. Refusing here would mean a site that reports
        # its disk oddly can never vacuum, and SQLite will fail safely anyway.
        return True
    return free >= needed


@dataclass(slots=True)
class TrimReport:
    """What a trim pass took, and what it could not."""

    examined: int = 0
    deleted: int = 0
    held: int = 0
    by_class: dict[str, int] = field(default_factory=dict)
    stopped: str = ""

    @property
    def idle(self) -> bool:
        return self.deleted == 0

    def note(self, klass: str) -> None:
        self.by_class[klass] = self.by_class.get(klass, 0) + 1

    def __str__(self) -> str:
        end = f", stopped: {self.stopped}" if self.stopped else ""
        return f"trimmed {self.deleted} of {self.examined} examined, {self.held} held{end}"


@dataclass(slots=True)
class Trimmer:
    """Takes records the retention policy would have kept, when space runs out."""

    connection: sqlite3.Connection
    path: Path
    cap_bytes: int
    table: PriorityTable = field(default_factory=PriorityTable)
    batch: int = DEFAULT_TRIM_BATCH

    def __post_init__(self) -> None:
        self.path = Path(self.path)
        if self.cap_bytes <= 0:
            raise RetentionError("the archive cap must be positive", detail=str(self.cap_bytes))
        if self.batch < 1:
            raise RetentionError("the trim batch must be at least one")

    def footprint(self) -> Footprint:
        return measure(self.connection, self.path)

    def over_by(self) -> int:
        return max(self.footprint().used - self.cap_bytes, 0)

    def order(self) -> list[str]:
        """Which class gets trimmed first: the least important one.

        This is the spool's eviction order, which is the same question asked
        about a different disk, and reusing it means the two cannot disagree
        about which records matter. It leaves protected classes out entirely
        rather than putting them last, so running off the end of the list
        cannot delete one.
        """
        return list(self.table.evict_order())

    def candidates(self, klass: str, limit: int) -> list[sqlite3.Row]:
        return list(
            self.connection.execute(
                f"SELECT id, klass, {ORDER_COLUMN} AS at FROM records"
                " WHERE klass = ? ORDER BY at ASC LIMIT ?",
                (klass, limit),
            )
        )

    def run(self, holds: list[Hold], now: NanoTime) -> TrimReport:
        """Trim until the archive is under its cap, or until nothing may go."""
        report = TrimReport()
        if not self.over_by():
            report.stopped = "already under the cap"
            return report
        for klass in self.order():
            while self.over_by():
                rows = self.candidates(klass, self.batch)
                if not rows:
                    break
                if not self._trim_rows(rows, holds, report):
                    break
        if self.over_by():
            report.stopped = "nothing left that may be deleted"
        return report

    def _trim_rows(self, rows: list[sqlite3.Row], holds: list[Hold], report: TrimReport) -> bool:
        """Delete what may go out of one batch. False when none of it may."""
        doomed: list[int] = []
        for row in rows:
            report.examined += 1
            when = NanoTime(int(row["at"]))
            if any(hold.covers(when) for hold in holds):
                report.held += 1
                continue
            doomed.append(int(row["id"]))
            report.note(str(row["klass"]))
        if not doomed:
            return False
        with self.connection:
            self.connection.executemany(
                "DELETE FROM records WHERE id = ?", [(item,) for item in doomed]
            )
        report.deleted += len(doomed)
        return True
