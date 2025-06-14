"""Every file on the box, followed together.

The engine owns the set of pipelines and keeps it in step with what is actually
on disk. Containers come and go on a forecourt all day: the pump bridge restarts
on every dispenser reset, the loyalty sync is a cron job that exists for ninety
seconds, and the price sign driver is only running when head office pushed a
price. So the set of files worth following is not something to work out once at
startup.

A pipeline that is dropped is closed first, because whatever it was holding is
the last thing that container ever said and it will not be said again.

Format detection happens once per file, on a sample of its first bytes, and the
answer is kept for the life of the pipeline. Re-detecting per poll would let a
file change format halfway through, which cannot happen and would only give the
detector a chance to be wrong twice.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from stanchion.clocks import Clock, SystemClock
from stanchion.config import SiteConfig
from stanchion.errors import ParseError
from stanchion.eventlog import EventLog, null_log
from stanchion.ingest.checkpoint import CheckpointStore
from stanchion.ingest.detect import SAMPLE_BYTES, Detection, detect
from stanchion.ingest.discover import Discovery, discover
from stanchion.ingest.join import JoinLimits
from stanchion.ingest.multiline import MultilineRule, Stitcher
from stanchion.ingest.pipeline import UnitPipeline
from stanchion.ingest.readers import reader_for
from stanchion.paths import LogPathInfo
from stanchion.records import LogRecord
from stanchion.telemetry import Registry


@dataclass(slots=True)
class IngestEngine:
    """Discovery, one pipeline per followed file, and the poll loop."""

    config: SiteConfig
    checkpoints: CheckpointStore | None = None
    events: EventLog = field(default_factory=null_log)
    metrics: Registry = field(default_factory=Registry)
    rules: dict[str, MultilineRule] = field(default_factory=dict)
    clock: Clock = field(default_factory=SystemClock)
    pipelines: dict[str, UnitPipeline] = field(default_factory=dict, init=False)
    detections: dict[str, Detection] = field(default_factory=dict, init=False)

    @property
    def limits(self) -> JoinLimits:
        return JoinLimits(max_line_bytes=self.config.ingest.max_line_bytes)

    def refresh(self) -> Discovery:
        """Bring the pipeline set in line with what is on disk."""
        found = discover(Path(self.config.ingest.log_root), self.config.ingest.units)
        wanted = {info.path: info for info in found.follow}
        for path in list(self.pipelines):
            if path not in wanted:
                self._drop(path)
        for path, info in wanted.items():
            if path not in self.pipelines:
                self._add(info)
        self.metrics.gauge("ingest_files_followed").set(len(self.pipelines))
        for reason, count in found.reasons().items():
            self.metrics.counter(f"ingest_skipped_{reason.value}").inc(count)
        return found

    def poll(self) -> list[LogRecord]:
        """One pass over every followed file."""
        out: list[LogRecord] = []
        for path in sorted(self.pipelines):
            pipeline = self.pipelines[path]
            records = pipeline.poll()
            out.extend(records)
            if records:
                self.metrics.incr("ingest_records", len(records))
        return out

    def close(self) -> list[LogRecord]:
        """Shut everything down, emitting what each pipeline still holds."""
        out: list[LogRecord] = []
        for path in sorted(self.pipelines):
            out.extend(self.pipelines[path].close())
        self.pipelines.clear()
        self.detections.clear()
        self.metrics.gauge("ingest_files_followed").set(0)
        return out

    def _add(self, info: LogPathInfo) -> None:
        try:
            found = detect(info.path, self._sample(Path(info.path)))
        except ParseError as exc:
            self.metrics.incr("ingest_undetectable")
            self.events.exception("format_undetected", exc, path=info.path)
            return
        if found.disagreed:
            # The box was upgraded in place and nobody wrote it down.
            self.metrics.incr("ingest_layout_disagreement")
            self.events.warn(
                "layout_disagreement",
                path=info.path,
                layout=str(found.path_layout),
                format=found.format.value,
            )
        pipeline = UnitPipeline(
            site=self.config.site,
            info=info,
            reader=reader_for(found.format),
            limits=self.limits,
            stitcher=self._stitcher(info.unit),
            checkpoints=self.checkpoints,
            clock=self.clock,
        )
        resumed = pipeline.resume()
        self.pipelines[info.path] = pipeline
        self.detections[info.path] = found
        self.metrics.incr("ingest_files_opened")
        self.events.info(
            "file_opened",
            path=info.path,
            unit=info.unit,
            format=found.format.value,
            resumed=resumed,
        )

    def _drop(self, path: str) -> None:
        pipeline = self.pipelines.pop(path)
        self.detections.pop(path, None)
        left = pipeline.close()
        self.metrics.incr("ingest_files_closed")
        self.events.info("file_closed", path=path, flushed=len(left))

    def _stitcher(self, unit: str) -> Stitcher | None:
        rule = self.rules.get(unit)
        return None if rule is None else Stitcher(rule=rule)

    def _sample(self, path: Path) -> bytes:
        try:
            with path.open("rb") as handle:
                return handle.read(SAMPLE_BYTES)
        except OSError:
            return b""


def engine_for(config: SiteConfig, **extra: object) -> IngestEngine:
    """Build an engine and open everything that is already there."""
    engine = IngestEngine(config=config, **extra)  # type: ignore[arg-type]
    engine.refresh()
    return engine
