"""Putting the correction onto the records.

There is a gap between the drift model and the records, and it has to be named
rather than papered over: **a CRI log line carries no monotonic reading.** The
drift model is defined over positions in a boot session, and a log line only has
a wall clock timestamp, which is the thing we do not trust.

The way across is that both readings come from the *same* wrong clock. The
aggregator knows what that clock says right now, and what the monotonic counter
says right now. The interval between now and the record's timestamp is measured
entirely in that one clock's own units, so the error very nearly cancels:

    position = monotonic_now - (wall_now - record_wall)

What survives is second order, the drift accumulated across the interval itself,
which for a record read seconds after it was written is nanoseconds. The
approximation gets worse the longer the record sat unread, so a backlog read
after an outage is exactly where it is weakest, and that is also where the
records are furthest from any sync and already marked extrapolated.

A journald record is the exception. It carries ``__MONOTONIC_TIMESTAMP``, which
is the real position, so where that is present it is used and no estimate is
made at all.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace

from stanchion.clockfix.drift import Confidence, Correction, DriftModel
from stanchion.clockfix.session import BootSession, SessionLedger
from stanchion.clocks import Clock, SystemClock
from stanchion.records import LogRecord
from stanchion.timestamps import NS_PER_MS, NanoTime

MONOTONIC_LABEL = "monotonic_ns"
BOOT_LABEL = "boot_id"
DRIFT_LABEL = "clock_drift_ms"


@dataclass(slots=True)
class ReconcileStats:
    """What the correction pass did, for the nightly summary."""

    seen: int = 0
    corrected: int = 0
    measured: int = 0
    interpolated: int = 0
    extrapolated: int = 0
    unknown: int = 0
    stale: int = 0
    from_monotonic: int = 0

    def note(self, correction: Correction, exact_position: bool) -> None:
        self.seen += 1
        if correction.offset_ns:
            self.corrected += 1
        if exact_position:
            self.from_monotonic += 1
        if correction.stale:
            self.stale += 1
        counter = {
            Confidence.MEASURED: "measured",
            Confidence.INTERPOLATED: "interpolated",
            Confidence.EXTRAPOLATED: "extrapolated",
            Confidence.UNKNOWN: "unknown",
        }[correction.confidence]
        setattr(self, counter, getattr(self, counter) + 1)


@dataclass(slots=True)
class Reconciler:
    """Applies one session's drift model to records as they go past."""

    ledger: SessionLedger
    boot_id: str
    clock: Clock = field(default_factory=SystemClock)
    stats: ReconcileStats = field(default_factory=ReconcileStats)

    @property
    def session(self) -> BootSession:
        return self.ledger.session(self.boot_id)

    @property
    def model(self) -> DriftModel:
        return DriftModel(session=self.session)

    def position_of(self, reading: NanoTime) -> int:
        """Estimate where in this boot session a wall clock reading sits."""
        elapsed = self.clock.now() - reading
        return max(0, self.clock.monotonic_ns() - elapsed)

    def reconcile(self, record: LogRecord) -> LogRecord:
        """Return the record with a corrected time and a confidence on it."""
        stated = record.labels.get(MONOTONIC_LABEL)
        exact = stated is not None
        if stated is not None:
            position = int(stated)
        else:
            position = self.position_of(record.observed_at)
        correction = self.model.correction_at(position)
        self.stats.note(correction, exact)
        labels = dict(record.labels)
        if correction.offset_ns:
            labels[DRIFT_LABEL] = str(round(correction.offset_ns / NS_PER_MS))
        if correction.stale:
            labels["clock_stale"] = "true"
        labels[BOOT_LABEL] = self.session.boot_id
        return replace(
            record,
            corrected_at=correction.apply(record.observed_at),
            clock_confidence=correction.confidence.value,
            labels=labels,
        )

    def reconcile_all(self, records: list[LogRecord]) -> list[LogRecord]:
        return [self.reconcile(record) for record in records]
