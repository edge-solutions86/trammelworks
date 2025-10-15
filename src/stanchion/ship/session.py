"""One pass of the shipper.

The loop is ordinary. What it has to get right is the order of the four
decisions, and each one can stop the pass:

1. **which segment.** Highest priority class first, oldest first inside it. A
   site with a backlog ships payment records from three days ago before it ships
   this morning's price sign chatter.
2. **has the meter got room for this class.** The floors mean the answer differs
   per class, so a pass that cannot ship chatter can still ship payment.
3. **did it go.** Only an acknowledgement moves the watermark.
4. **should we carry on.** A retryable failure stops the whole pass rather than
   moving to the next segment. The link is down, and hammering it with the next
   segment wastes the metered bytes that the backoff exists to protect.

A segment is pinned for as long as the pass is working on it, so an eviction
running underneath cannot delete what is being uploaded. Once every record in it
is acknowledged it is deleted here rather than left for eviction, because the
sooner it goes the more room a site with no uplink has for what comes next.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from stanchion.clocks import Clock, SystemClock
from stanchion.errors import ShipError, SpoolError
from stanchion.eventlog import EventLog, null_log
from stanchion.ship.ack import AckLedger
from stanchion.ship.backoff import Backoff, Outcome
from stanchion.ship.batch import Batch, BatchLimits, cut_batches
from stanchion.ship.meter import Meter
from stanchion.ship.transport import Transport
from stanchion.spool.priority import PriorityTable
from stanchion.spool.reader import read_segment
from stanchion.spool.segment import SegmentInfo, list_segments
from stanchion.telemetry import Registry


@dataclass(slots=True)
class ShipReport:
    """What one pass did."""

    batches_sent: int = 0
    records_sent: int = 0
    bytes_sent: int = 0
    segments_completed: int = 0
    segments_touched: int = 0
    blocked_by_meter: list[str] = field(default_factory=list)
    wait_seconds: float = 0.0
    stopped: str = ""
    terminal: int = 0

    @property
    def idle(self) -> bool:
        return self.batches_sent == 0 and not self.stopped


@dataclass(slots=True)
class Shipper:
    """Moves acknowledged records off the site, inside the allowance."""

    site: str
    directory: Path
    transport: Transport
    meter: Meter = field(default_factory=Meter)
    acks: AckLedger = field(default_factory=AckLedger)
    table: PriorityTable = field(default_factory=PriorityTable)
    limits: BatchLimits = field(default_factory=BatchLimits)
    backoff: Backoff = field(default_factory=Backoff)
    clock: Clock = field(default_factory=SystemClock)
    events: EventLog = field(default_factory=null_log)
    metrics: Registry = field(default_factory=Registry)
    delete_acknowledged: bool = True
    pinned: set[Path] = field(default_factory=set, init=False)

    def order(self) -> list[SegmentInfo]:
        """Sealed segments, in the order they should be shipped."""
        rank = {name: index for index, name in enumerate(self.table.ship_order())}
        found = [item for item in list_segments(self.directory) if item.klass in rank]
        found.sort(key=lambda item: (rank[item.klass], item.sequence))
        return found

    def run_once(self) -> ShipReport:
        """One pass over the spool."""
        report = ShipReport()
        self.meter.roll(self.clock.now())
        for segment in self.order():
            if not self._ship_segment(segment, report):
                break
        self.acks.sweep({item.name for item in list_segments(self.directory)})
        return report

    def _ship_segment(self, segment: SegmentInfo, report: ShipReport) -> bool:
        """Ship what is left of one segment. Returns whether to keep going."""
        try:
            found = read_segment(segment.path)
        except SpoolError as exc:
            self.events.exception("segment_unreadable", exc, segment=segment.name)
            self.metrics.incr("ship_unreadable_segments")
            return True
        payloads = found.payloads
        mark = self.acks.mark(segment.name, len(payloads))
        if mark.done:
            self._retire(segment)
            return True
        report.segments_touched += 1
        self.pinned.add(segment.path)
        try:
            return self._send_batches(segment, payloads[mark.acked :], report)
        finally:
            self.pinned.discard(segment.path)

    def _send_batches(
        self, segment: SegmentInfo, remaining: list[dict[str, object]], report: ShipReport
    ) -> bool:
        for batch in cut_batches(self.site, remaining, self.limits, source=segment.name):
            size = batch.wire_bytes
            if not self.meter.allows(size, segment.klass):
                report.blocked_by_meter.append(segment.name)
                self.metrics.incr("ship_meter_blocked")
                self.events.warn(
                    "meter_blocked",
                    segment=segment.name,
                    klass=segment.klass,
                    available=self.meter.available_to(segment.klass),
                )
                return True
            if not self._send_one(segment, batch, size, report):
                return False
        mark = self.acks.marks[segment.name]
        if mark.done:
            report.segments_completed += 1
            self._retire(segment)
        return True

    def _send_one(self, segment: SegmentInfo, batch: Batch, size: int, report: ShipReport) -> bool:
        mark = self.acks.marks[segment.name]
        mark.attempts += 1
        answer = self.transport.send(batch)
        self.meter.charge(answer.bytes_sent or size)
        report.bytes_sent += answer.bytes_sent or size
        self.metrics.incr("ship_bytes", answer.bytes_sent or size)
        if answer.outcome is Outcome.OK:
            self.backoff.succeed()
            self.acks.acknowledge(segment.name, batch.count)
            report.batches_sent += 1
            report.records_sent += batch.count
            self.metrics.incr("ship_batches")
            self.metrics.incr("ship_records", batch.count)
            return True
        if answer.outcome is Outcome.TERMINAL:
            mark.failed += 1
            report.terminal += 1
            self.metrics.incr("ship_terminal")
            self.events.error(
                "batch_refused",
                segment=segment.name,
                batch=batch.batch_id,
                status=answer.status,
                message=answer.message,
            )
            return False
        report.wait_seconds = self.backoff.record(answer.outcome, answer.retry_after)
        report.stopped = answer.message or answer.outcome.value
        self.metrics.incr("ship_retryable")
        self.events.warn(
            "upload_failed",
            segment=segment.name,
            status=answer.status,
            wait=round(report.wait_seconds, 3),
            failures=self.backoff.failures,
        )
        return False

    def _retire(self, segment: SegmentInfo) -> None:
        """Everything in this segment is acknowledged, so it can go."""
        if not self.delete_acknowledged:
            return
        try:
            segment.path.unlink()
        except FileNotFoundError:
            pass
        except OSError as exc:
            raise ShipError("cannot remove an acknowledged segment", detail=str(exc)) from exc
        self.acks.forget(segment.name)
        self.metrics.incr("ship_segments_retired")
        self.events.info("segment_retired", segment=segment.name)
