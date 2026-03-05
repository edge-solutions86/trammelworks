"""One forecourt, end to end.

This is the piece that says what order things happen in, and the order is the
design:

    read -> reassemble -> correct the clock -> redact -> spool -> ship

Two of those placements are worth defending.

**Redaction sits before the spool, not before the ship.** The spool is a file on
a disk in a cabinet under a shop counter, and that disk gets pulled, imaged and
posted to a lab when a controller dies. Cardholder data that reached the spool
has left our control whatever the shipper does afterwards.

**The archive gets the same redacted record the spool does.** It is written
after the spool, not instead of it, and the two answer different questions: the
spool is what the estate will eventually see, the archive is what this site can
still answer from when the estate asks six weeks later. A record that failed to
reach the spool is still archived, because a record we could not send is exactly
the one somebody will come asking about.

**Clock correction sits before redaction.** Correction reads the records of the
time daemon to find out when the clock was fixed, and redaction rewrites message
text. Running redaction first would mean a site rule could eat the very line the
correction depends on, and the failure would look like a clock that never syncs.

A cycle is collect then ship, and it is safe to stop between the two. Records
that reached the spool are durable; anything that did not is still in the log
files the container wrote, and the checkpoint has not moved past it.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath

from stanchion.archive.repository import Repository
from stanchion.archive.schema import open_archive
from stanchion.archive.sweeper import Sweeper, SweepReport
from stanchion.clockfix.host import current_boot_identity
from stanchion.clockfix.observe import SyncObserver
from stanchion.clockfix.reconcile import Reconciler
from stanchion.clockfix.store import ClockStore
from stanchion.clocks import Clock, SystemClock
from stanchion.config import SiteConfig
from stanchion.eventlog import EventLog, null_log
from stanchion.ingest.checkpoint import CheckpointStore
from stanchion.ingest.engine import IngestEngine
from stanchion.records import LogRecord
from stanchion.redact.engine import Redactor
from stanchion.ship.ack import AckStore
from stanchion.ship.compact import CompactionResult, Compactor, clear_sidecars
from stanchion.ship.config import MeterStore
from stanchion.ship.session import Shipper, ShipReport
from stanchion.ship.transport import FileTransport, HttpTransport, Transport
from stanchion.spool.store import Spool
from stanchion.telemetry import Registry

STATE_DIRNAME = "state"
CHECKPOINT_DIRNAME = "checkpoints"


def state_directory(spool_directory: str) -> Path:
    """State lives beside the spool, never inside it.

    Inside, an eviction sweep would be free to delete the checkpoints and the
    meter, and the site would come back thinking it had never read or sent
    anything.
    """
    return Path(PurePosixPath(spool_directory).parent / STATE_DIRNAME)


@dataclass(slots=True)
class CollectReport:
    """What one collect pass moved."""

    read: int = 0
    shipped_to_spool: int = 0
    archived: int = 0
    redacted: int = 0
    syncs_seen: int = 0
    refused: int = 0

    @property
    def idle(self) -> bool:
        return self.read == 0


@dataclass(slots=True)
class CycleReport:
    """A collect and a ship, together."""

    collect: CollectReport = field(default_factory=CollectReport)
    ship: ShipReport = field(default_factory=ShipReport)

    @property
    def idle(self) -> bool:
        return self.collect.idle and self.ship.idle


def build_transport(config: SiteConfig) -> Transport:
    """The transport this site's endpoint asks for."""
    settings = config.ship
    if settings.to_file:
        return FileTransport(directory=settings.file_directory)
    return HttpTransport(
        endpoint=settings.endpoint,
        timeout=settings.attempt_timeout.total_seconds(),
        token=settings.token,
    )


@dataclass(slots=True)
class Site:
    """Everything one forecourt controller runs."""

    config: SiteConfig
    transport: Transport
    clock: Clock = field(default_factory=SystemClock)
    events: EventLog = field(default_factory=null_log)
    metrics: Registry = field(default_factory=Registry)
    engine: IngestEngine = field(init=False)
    spool: Spool = field(init=False)
    shipper: Shipper = field(init=False)
    redactor: Redactor = field(init=False)
    reconciler: Reconciler = field(init=False)
    observer: SyncObserver = field(init=False)
    clock_store: ClockStore = field(init=False)
    meter_store: MeterStore = field(init=False)
    ack_store: AckStore = field(init=False)
    archive: Repository | None = field(default=None, init=False)
    sweeper: Sweeper | None = field(default=None, init=False)

    @classmethod
    def build(
        cls,
        config: SiteConfig,
        transport: Transport | None = None,
        clock: Clock | None = None,
        events: EventLog | None = None,
        metrics: Registry | None = None,
    ) -> Site:
        return cls(
            config=config,
            transport=transport if transport is not None else build_transport(config),
            clock=clock if clock is not None else SystemClock(),
            events=events if events is not None else null_log(),
            metrics=metrics if metrics is not None else Registry(),
        )

    def __post_init__(self) -> None:
        state = state_directory(self.config.spool.directory)
        self.clock_store = ClockStore(directory=state)
        self.meter_store = MeterStore(directory=state)
        self.ack_store = AckStore(directory=state)
        boot = current_boot_identity(state)
        ledger = self.clock_store.load()
        ledger.session(boot.boot_id)
        self.observer = SyncObserver(ledger=ledger, default_boot_id=boot.boot_id)
        self.reconciler = Reconciler(ledger=ledger, boot_id=boot.boot_id, clock=self.clock)
        self.redactor = Redactor(
            rules=list(self.config.redact.rules),
            keep_first=self.config.redact.keep_first,
            keep_last=self.config.redact.keep_last,
        )
        self.engine = IngestEngine(
            config=self.config,
            checkpoints=CheckpointStore(directory=state / CHECKPOINT_DIRNAME),
            events=self.events,
            metrics=self.metrics,
            rules={rule.unit: rule for rule in self.config.ingest.multiline},
            clock=self.clock,
        )
        self.spool = Spool(
            directory=self.config.spool.path,
            budget=self.config.spool.budget(),
            table=self.config.spool.table(),
            segment_bytes=self.config.spool.segment_bytes,
            sync_every=self.config.spool.sync_every,
            compress=self.config.spool.compress,
            events=self.events,
            metrics=self.metrics,
        )
        self.shipper = Shipper(
            site=self.config.site,
            directory=self.config.spool.path,
            transport=self.transport,
            meter=self.meter_store.load(self.config.ship),
            acks=self.ack_store.load(),
            table=self.config.spool.table(),
            limits=self.config.ship.limits(),
            clock=self.clock,
            events=self.events,
            metrics=self.metrics,
            delete_acknowledged=self.config.ship.delete_acknowledged,
        )

    def open(self) -> None:
        """Recover anything an interrupted run left and pick up the files."""
        stray = clear_sidecars(self.config.spool.path)
        if stray:
            self.events.warn("compaction_sidecars_cleared", count=len(stray))
        self.spool.open()
        self._open_archive()
        self.engine.refresh()
        self.events.info("site_open", site=self.config.site, files=len(self.engine.pipelines))

    def _open_archive(self) -> None:
        """Open the archive, unless this site is one that does without."""
        settings = self.config.archive
        if not settings.enabled:
            return
        connection = open_archive(settings.file)
        self.archive = Repository(connection=connection, table=self.config.spool.table())
        self.sweeper = Sweeper(
            connection=connection,
            policy=settings.retention,
            max_deletes=settings.max_deletes,
        )
        self.events.info("archive_open", path=str(settings.file), records=self.archive.count())

    def collect(self) -> CollectReport:
        """Read what is new and put it in the spool."""
        report = CollectReport()
        self.engine.refresh()
        records = self.engine.poll()
        report.read = len(records)
        self._absorb(records, report)
        return report

    def absorb(self, records: list[LogRecord]) -> CollectReport:
        """Put records that came from somewhere other than the tailer through.

        The backfill reads rotated files and hands them here, so they go through
        exactly the same correction, redaction, spool and archive path as the
        live ones. A second route into the spool that skipped redaction is the
        one bug this system cannot afford.
        """
        report = CollectReport(read=len(records))
        self._absorb(records, report)
        return report

    def _absorb(self, records: list[LogRecord], report: CollectReport) -> None:
        # The archive is fed the CLEANED records, not the ones that came in.
        # Handing it the originals would put cardholder data in the one file on
        # the box that is designed to still be there in six months.
        cleaned_all: list[LogRecord] = []
        for record in records:
            self.observer.look(record)
            corrected = self.reconciler.reconcile(record)
            cleaned = self.redactor.redact(corrected)
            if cleaned is not corrected:
                report.redacted += 1
            cleaned_all.append(cleaned)
            try:
                self.spool.append(cleaned)
            except Exception as exc:  # noqa: BLE001 - reported, never raised on
                report.refused += 1
                self.metrics.incr("site_spool_refused")
                self.events.exception("spool_refused", exc, unit=record.unit)
                continue
            report.shipped_to_spool += 1
        report.archived += self._archive(cleaned_all)
        report.syncs_seen = self.observer.filed

    def _archive(self, records: list[LogRecord]) -> int:
        """Keep a local copy. A failure here is reported, never raised on.

        The archive answers disputes and nothing on the forecourt stops if it
        cannot be written. Losing the shipment would be a different matter, and
        that path does not come through here.
        """
        if self.archive is None:
            return 0
        try:
            return self.archive.add_all(records, self.clock.now())
        except Exception as exc:  # noqa: BLE001 - reported, never raised on
            self.metrics.incr("site_archive_failed")
            self.events.exception("archive_write_failed", exc)
            return 0

    def ship(self) -> ShipReport:
        """One pass of the uplink."""
        self.spool.seal_all()
        report = self.shipper.run_once()
        self.compact()
        self.persist()
        return report

    def compact(self) -> CompactionResult:
        """Give back the disk that acknowledged records are still holding.

        After the pass, not before. A segment the shipper just worked on is the
        one most likely to be part acknowledged, and doing it here means the
        rewrite happens while nothing is reading, rather than in the middle of
        cutting batches out of the same file.
        """
        settings = self.config.ship
        if not settings.compact:
            return CompactionResult()
        compactor = Compactor(
            directory=self.config.spool.path,
            ledger=self.shipper.acks,
            min_reclaim=settings.compact_min_reclaim_bytes,
            min_fraction=settings.compact_min_fraction,
            events=self.events,
            metrics=self.metrics,
        )
        result = compactor.run(self.spool.segments(), pinned=frozenset(self.shipper.pinned))
        if result.segments:
            self.events.info("spool_compacted", segments=result.segments, freed=result.reclaimed)
        return result

    def cycle(self) -> CycleReport:
        """Collect, then ship."""
        return CycleReport(collect=self.collect(), ship=self.ship())

    def sweep(self) -> SweepReport:
        """Apply retention to the archive."""
        if self.sweeper is None:
            return SweepReport()
        report = self.sweeper.sweep(self.clock.now())
        if report.deleted:
            self.metrics.incr("archive_deleted", report.deleted)
            self.events.info("archive_swept", deleted=report.deleted, held=report.held)
        return report

    def snapshot(self) -> dict[str, int]:
        """Every counter the site keeps, in one mapping.

        Gathered rather than stored, so it is always the live numbers. The
        nightly summary and the end to end tests both read this, which is the
        point: a test that asserts on the same numbers an operator sees cannot
        drift away from what the operator is told.
        """
        found = dict(self.metrics.snapshot())
        found["spool_limit"] = self.config.spool.budget_bytes
        found["spool_used"] = self.spool.usage().used
        found["ship_meter_used"] = self.shipper.meter.used_bytes
        found["ship_meter_allowance"] = self.shipper.meter.allowance_bytes
        found["ship_outstanding"] = self.shipper.acks.outstanding
        found["redact_records"] = self.redactor.audit.records
        found["redact_changed"] = self.redactor.audit.changed
        found["redact_removed"] = self.redactor.audit.total_hits
        found["clock_syncs"] = self.observer.filed
        if self.archive is not None:
            found["archive_records"] = self.archive.count()
        return dict(sorted(found.items()))

    def persist(self) -> None:
        """Write the state that must survive a power cut."""
        self.meter_store.save(self.shipper.meter)
        self.ack_store.save(self.shipper.acks)
        self.clock_store.save(self.observer.ledger)

    def close(self) -> CollectReport:
        """Shut down: flush the readers, seal the spool, save the state."""
        report = CollectReport()
        records = self.engine.close()
        report.read = len(records)
        self._absorb(records, report)
        self.spool.seal_all()
        self.persist()
        self.events.info("site_closed", site=self.config.site)
        return report
