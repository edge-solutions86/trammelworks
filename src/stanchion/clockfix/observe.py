"""Spotting a clock correction in the log stream itself.

There is no API on a site controller that says "the clock was just fixed". The
only evidence is that the time daemon writes a line about it, so the aggregator
reads its own input for those lines.

Two daemons appear across the estate and they say it differently:

* **chronyd** logs ``System clock wrong by -1.234567 seconds, adjustment
  started``. The number is how wrong the clock was, signed so that a *negative*
  value means the clock was behind. That sign convention is the opposite of the
  one that reads naturally, and getting it backwards doubles the error instead
  of removing it, so it is stated here and tested in both directions.
* **systemd-timesyncd** logs ``Initial synchronization to time server
  10.0.0.1:123 (ntp.internal).`` with no number at all. That still tells us the
  clock is right *from now on*, which is worth recording even though the size of
  the correction it made is unknown.

An observation is only as good as its position in the boot session, so a record
with no monotonic label cannot produce a sync event. Guessing one would put a
measured correction at an estimated place, which is the worst of both.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from stanchion.clockfix.session import SessionLedger, SyncEvent
from stanchion.errors import ConfigError
from stanchion.records import LogRecord
from stanchion.timestamps import NS_PER_SECOND, NanoTime

MONOTONIC_LABEL = "monotonic_ns"
BOOT_LABEL = "boot_id"

CHRONY_UNITS = frozenset({"chronyd", "chrony"})
TIMESYNCD_UNITS = frozenset({"systemd-timesyncd", "timesyncd"})

CHRONY_STEP = re.compile(r"System clock wrong by (?P<seconds>[-+]?\d+(?:\.\d+)?) seconds")
TIMESYNCD_SYNC = re.compile(r"(?:Initial s|S)ynchronization to time server")


@dataclass(frozen=True, slots=True)
class Observation:
    """A sync spotted in the stream, before it is filed against a session."""

    event: SyncEvent
    unit: str
    exact: bool

    @property
    def boot_id(self) -> str:
        return self.event.boot_id


def _position(record: LogRecord) -> int | None:
    raw = record.labels.get(MONOTONIC_LABEL)
    if raw is None:
        return None
    try:
        return int(raw)
    except ValueError:
        return None


def observe_chrony(record: LogRecord, boot_id: str) -> Observation | None:
    """Read a chronyd step line, if that is what this is."""
    match = CHRONY_STEP.search(record.message)
    if match is None:
        return None
    position = _position(record)
    if position is None:
        return None
    wrong_by = float(match.group("seconds"))
    # chronyd's number is (clock - truth). The clock read record.observed_at, so
    # the truth at that instant was that reading minus the error.
    error_ns = round(wrong_by * NS_PER_SECOND)
    return Observation(
        event=SyncEvent(
            boot_id=boot_id,
            monotonic_ns=position,
            before=record.observed_at,
            after=record.observed_at.shifted(-error_ns),
            source="chrony",
        ),
        unit=record.unit,
        exact=True,
    )


def observe_timesyncd(record: LogRecord, boot_id: str) -> Observation | None:
    """Read a timesyncd sync line, which carries no measurement."""
    if TIMESYNCD_SYNC.search(record.message) is None:
        return None
    position = _position(record)
    if position is None:
        return None
    return Observation(
        event=SyncEvent(
            boot_id=boot_id,
            monotonic_ns=position,
            before=record.observed_at,
            after=record.observed_at,
            source="timesyncd",
        ),
        unit=record.unit,
        exact=False,
    )


@dataclass(slots=True)
class SyncObserver:
    """Watches the record stream and files what it finds in the ledger."""

    ledger: SessionLedger
    default_boot_id: str
    seen: int = 0
    filed: int = 0
    ignored_no_position: int = 0
    units: frozenset[str] = field(default=CHRONY_UNITS | TIMESYNCD_UNITS)

    def boot_of(self, record: LogRecord) -> str:
        stated = record.labels.get(BOOT_LABEL)
        return stated if stated else self.default_boot_id

    def look(self, record: LogRecord) -> Observation | None:
        """Inspect one record. Files the event when there is one."""
        if record.unit not in self.units:
            return None
        self.seen += 1
        boot_id = self.boot_of(record)
        try:
            if record.unit in CHRONY_UNITS:
                found = observe_chrony(record, boot_id)
            else:
                found = observe_timesyncd(record, boot_id)
        except ConfigError:
            return None
        if found is None:
            if _position(record) is None and _looks_like_a_sync(record.message):
                self.ignored_no_position += 1
            return None
        self.ledger.add_sync(found.event)
        self.filed += 1
        return found

    def look_all(self, records: list[LogRecord]) -> list[Observation]:
        found = [self.look(record) for record in records]
        return [item for item in found if item is not None]


def _looks_like_a_sync(message: str) -> bool:
    return CHRONY_STEP.search(message) is not None or TIMESYNCD_SYNC.search(message) is not None


def truth_at(record: LogRecord, wrong_by_seconds: float) -> NanoTime:
    """What the clock should have read, given how wrong chronyd says it was."""
    return record.observed_at.shifted(-round(wrong_by_seconds * NS_PER_SECOND))
