from __future__ import annotations

import json
from pathlib import Path

from stanchion.clocks import ManualClock
from stanchion.config import IngestConfig, SiteConfig
from stanchion.eventlog import EventLog, Level
from stanchion.ingest.checkpoint import CheckpointStore
from stanchion.ingest.engine import IngestEngine, engine_for
from stanchion.ingest.multiline import MultilineRule
from stanchion.telemetry import Registry
from tests.support.logfiles import SiteTree, cri_full, cri_line

SITE = "US-TX-0417"
TS = "2025-03-04T11:30:00.123456789Z"
CID = "3f" * 32


def config_for(tree: SiteTree, units: tuple[str, ...] = ()) -> SiteConfig:
    return SiteConfig(site=SITE, ingest=IngestConfig(log_root=tree.root.as_posix(), units=units))


def build(tree: SiteTree, **over: object) -> IngestEngine:
    return IngestEngine(  # type: ignore[arg-type]
        config=over.pop("config", None) or config_for(tree),
        clock=ManualClock(),
        **over,
    )


def test_refresh_opens_what_is_on_disk(tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    engine = build(tree)
    found = engine.refresh()
    assert [info.unit for info in found.follow] == ["pump-bridge"]
    assert len(engine.pipelines) == 1


def test_poll_returns_the_records(tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "nozzle 1 lifted")])
    engine = build(tree)
    engine.refresh()
    assert [r.message for r in engine.poll()] == ["nozzle 1 lifted"]


def test_records_carry_the_site_and_unit(tree: SiteTree) -> None:
    tree.write_cri("tank-gauge", [cri_full(TS, "level 41200")])
    engine = build(tree)
    engine.refresh()
    record = engine.poll()[0]
    assert record.site == SITE
    assert record.unit == "tank-gauge"


def test_two_units_are_both_followed(tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    tree.write_cri("tank-gauge", [cri_full(TS, "two")])
    engine = build(tree)
    engine.refresh()
    assert sorted(r.message for r in engine.poll()) == ["one", "two"]


def test_a_second_poll_reads_only_what_is_new(tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    engine = build(tree)
    engine.refresh()
    engine.poll()
    tree.write_cri("pump-bridge", [cri_full(TS, "two")])
    assert [r.message for r in engine.poll()] == ["two"]


def test_refresh_picks_up_a_container_that_started_later(tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    engine = build(tree)
    engine.refresh()
    engine.poll()
    tree.write_cri("loyalty-sync", [cri_full(TS, "sync start")])
    engine.refresh()
    assert [r.message for r in engine.poll()] == ["sync start"]


def test_a_file_that_goes_away_is_closed(tree: SiteTree) -> None:
    path = tree.write_cri("loyalty-sync", [cri_full(TS, "one")])
    engine = build(tree)
    engine.refresh()
    engine.poll()
    path.unlink()
    engine.refresh()
    assert engine.pipelines == {}


def test_closing_a_dropped_file_flushes_what_it_held(tree: SiteTree) -> None:
    path = tree.write_cri("loyalty-sync", [cri_line(TS, "stdout", "P", "half a line")])
    engine = build(tree)
    engine.refresh()
    engine.poll()
    path.unlink()
    events = EventLog(minimum=Level.DEBUG, clock=ManualClock())
    engine.events = events
    engine.refresh()
    closed = [r for r in events.records() if r["event"] == "file_closed"]
    assert closed and closed[0]["flushed"] == 1


def test_close_emits_everything_held(tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_line(TS, "stdout", "P", "unfinished")])
    engine = build(tree)
    engine.refresh()
    engine.poll()
    assert [r.message for r in engine.close()] == ["unfinished"]
    assert engine.pipelines == {}


def test_the_unit_filter_narrows_what_is_followed(tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    tree.write_cri("tank-gauge", [cri_full(TS, "two")])
    engine = build(tree, config=config_for(tree, units=("pump-bridge",)))
    engine.refresh()
    assert [r.message for r in engine.poll()] == ["one"]


def test_json_file_read(tree: SiteTree) -> None:
    path = tree.docker_path(CID)
    payload = json.dumps({"log": "auth approved\n", "stream": "stdout", "time": TS})
    tree.write(path, payload + "\n")
    engine = build(tree)
    engine.refresh()
    assert [r.message for r in engine.poll()] == ["auth approved"]


def test_layout_clash(tree: SiteTree) -> None:
    path = tree.docker_path(CID)
    tree.write(path, cri_full(TS, "cri content in a docker tree"))
    events = EventLog(minimum=Level.DEBUG, clock=ManualClock())
    engine = build(tree, events=events)
    engine.refresh()
    assert any(r["event"] == "layout_disagreement" for r in events.records())
    assert engine.metrics.snapshot()["ingest_layout_disagreement"] == 1


def test_the_metrics_count_the_files_and_records(tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one") + cri_full(TS, "two")])
    metrics = Registry()
    engine = build(tree, metrics=metrics)
    engine.refresh()
    engine.poll()
    snapshot = metrics.snapshot()
    assert snapshot["ingest_files_followed"] == 1
    assert snapshot["ingest_files_opened"] == 1
    assert snapshot["ingest_records"] == 2


def test_the_followed_gauge_falls_to_zero_on_close(tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    metrics = Registry()
    engine = build(tree, metrics=metrics)
    engine.refresh()
    engine.close()
    assert metrics.snapshot()["ingest_files_followed"] == 0


def test_skips_are_counted_by_reason(tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    tree.root.mkdir(parents=True, exist_ok=True)
    (tree.root / "messages").write_text("not ours", encoding="utf-8")
    metrics = Registry()
    engine = build(tree, metrics=metrics)
    engine.refresh()
    assert metrics.snapshot()["ingest_skipped_unparseable"] == 1


def test_a_stitcher_is_applied_when_a_rule_names_the_unit(tree: SiteTree) -> None:
    tree.write_cri(
        "payment-kernel",
        [cri_full(TS, "boom"), cri_full(TS, "    at Pump.reset"), cri_full(TS, "next")],
    )
    rules = {"payment-kernel": MultilineRule(unit="payment-kernel", continuation=r"^\s+")}
    engine = build(tree, rules=rules)
    engine.refresh()
    assert [r.message for r in engine.poll()] == ["boom\n    at Pump.reset"]


def test_no_rule_means_no_stitching(tree: SiteTree) -> None:
    tree.write_cri("payment-kernel", [cri_full(TS, "boom"), cri_full(TS, "    at Pump.reset")])
    engine = build(tree)
    engine.refresh()
    assert len(engine.poll()) == 2


def test_checkpoints_let_a_restart_resume(tree: SiteTree, tmp_path: Path) -> None:
    store = CheckpointStore(directory=tmp_path / "ckpt")
    tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    first = build(tree, checkpoints=store)
    first.refresh()
    first.poll()
    tree.write_cri("pump-bridge", [cri_full(TS, "two")])
    second = build(tree, checkpoints=store)
    second.refresh()
    assert [r.message for r in second.poll()] == ["two"]


def test_engine_for_opens_immediately(tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    engine = engine_for(config_for(tree), clock=ManualClock())
    assert len(engine.pipelines) == 1


def test_an_empty_log_root_follows_nothing(tree: SiteTree) -> None:
    engine = build(tree)
    engine.refresh()
    assert engine.pipelines == {}
    assert engine.poll() == []
