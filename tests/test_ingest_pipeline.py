from __future__ import annotations

from pathlib import Path

import pytest

from stanchion.clocks import ManualClock
from stanchion.ingest.checkpoint import CheckpointStore
from stanchion.ingest.multiline import MultilineRule, Stitcher
from stanchion.ingest.pipeline import UnitPipeline
from stanchion.ingest.readers import CriReader
from stanchion.paths import parse_log_path
from tests.support.logfiles import SiteTree, cri_full, cri_line, cri_split

SITE = "US-TX-0417"
TS = "2025-03-04T11:30:00.123456789Z"


@pytest.fixture
def logfile(tree: SiteTree) -> Path:
    return tree.write_cri("payment-kernel", [])


def build(path: Path, **over: object) -> UnitPipeline:
    return UnitPipeline(  # type: ignore[arg-type]
        site=SITE,
        info=parse_log_path(path.as_posix()),
        reader=CriReader(),
        clock=ManualClock(),
        **over,
    )


def append(path: Path, text: str) -> None:
    with path.open("a", encoding="utf-8", newline="") as handle:
        handle.write(text)


def test_a_poll_with_nothing_new_gives_nothing(logfile: Path) -> None:
    assert build(logfile).poll() == []


def test_one_line_becomes_one_record(logfile: Path) -> None:
    append(logfile, cri_full(TS, "auth approved"))
    records = build(logfile).poll()
    assert [r.message for r in records] == ["auth approved"]
    assert records[0].site == SITE
    assert records[0].unit == "payment-kernel"


def test_split_chunks_become_one_record(logfile: Path) -> None:
    append(logfile, cri_split(TS, "a very long trace", at=6))
    records = build(logfile).poll()
    assert [r.message for r in records] == ["a very long trace"]


def test_a_chunk_still_open_at_the_end_of_a_poll_waits(logfile: Path) -> None:
    pipeline = build(logfile)
    append(logfile, cri_line(TS, "stdout", "P", "first half "))
    assert pipeline.poll() == []
    append(logfile, cri_line(TS, "stdout", "F", "second half"))
    assert [r.message for r in pipeline.poll()] == ["first half second half"]


def test_the_stats_follow_the_work(logfile: Path) -> None:
    pipeline = build(logfile)
    append(logfile, cri_full(TS, "one") + cri_full(TS, "two"))
    pipeline.poll()
    assert pipeline.stats.polls == 1
    assert pipeline.stats.fragments == 2
    assert pipeline.stats.records == 2


def test_a_rotation_flushes_what_was_held(tree: SiteTree, logfile: Path) -> None:
    pipeline = build(logfile)
    append(logfile, cri_line(TS, "stdout", "P", "unfinished when it rotated"))
    pipeline.poll()
    tree.rotate(logfile)
    append(logfile, cri_full(TS, "after"))
    records = pipeline.poll()
    assert [r.message for r in records] == ["unfinished when it rotated", "after"]
    assert records[0].labels["truncated"] == "true"
    assert pipeline.stats.rotations == 1


def test_a_truncation_flushes_and_is_counted(logfile: Path) -> None:
    pipeline = build(logfile)
    append(logfile, cri_line(TS, "stdout", "P", "open"))
    pipeline.poll()
    logfile.write_bytes(b"")
    records = pipeline.poll()
    assert [r.message for r in records] == ["open"]
    assert pipeline.stats.truncations == 1
    append(logfile, cri_full(TS, "fresh"))
    assert [r.message for r in pipeline.poll()] == ["fresh"]


def test_a_disappearance_is_counted(logfile: Path) -> None:
    pipeline = build(logfile)
    append(logfile, cri_full(TS, "one"))
    pipeline.poll()
    logfile.unlink()
    pipeline.poll()
    assert pipeline.stats.disappearances == 1


def test_close_flushes_an_open_line(logfile: Path) -> None:
    pipeline = build(logfile)
    append(logfile, cri_line(TS, "stdout", "P", "never finished"))
    pipeline.poll()
    records = pipeline.close()
    assert [r.message for r in records] == ["never finished"]


def test_close_on_a_clean_pipeline_gives_nothing(logfile: Path) -> None:
    assert build(logfile).close() == []


def test_the_stitcher_runs_after_the_joiner(logfile: Path) -> None:
    stitcher = Stitcher(rule=MultilineRule(unit="payment-kernel", continuation=r"^\s+"))
    pipeline = build(logfile, stitcher=stitcher)
    append(
        logfile,
        cri_full(TS, "IllegalStateException")
        + cri_full(TS, "    at Pump.reset")
        + cri_full(TS, "plain line"),
    )
    records = pipeline.poll()
    assert [r.message for r in records] == ["IllegalStateException\n    at Pump.reset"]
    assert pipeline.close()[0].message == "plain line"


def test_a_split_line_is_stitched_as_one_line_not_two(logfile: Path) -> None:
    stitcher = Stitcher(rule=MultilineRule(unit="payment-kernel", continuation=r"^\s+"))
    pipeline = build(logfile, stitcher=stitcher)
    append(logfile, cri_split(TS, "head    indented looking tail", at=4))
    pipeline.poll()
    records = pipeline.close()
    assert [r.message for r in records] == ["head    indented looking tail"]


def test_the_key_names_the_site_unit_and_attempt(logfile: Path) -> None:
    assert build(logfile).key == "US-TX-0417_payment-kernel_0"


def test_a_checkpoint_is_written_after_a_poll(tmp_path: Path, logfile: Path) -> None:
    store = CheckpointStore(directory=tmp_path / "ckpt")
    pipeline = build(logfile, checkpoints=store)
    text = cri_full(TS, "one")
    append(logfile, text)
    pipeline.poll()
    stored = store.load(pipeline.key)
    assert stored is not None
    assert stored.offset == len(text)


def test_the_checkpoint_excludes_bytes_that_are_not_yet_a_line(
    tmp_path: Path, logfile: Path
) -> None:
    store = CheckpointStore(directory=tmp_path / "ckpt")
    pipeline = build(logfile, checkpoints=store)
    complete = cri_full(TS, "one")
    append(logfile, complete + f"{TS} stdout F half")
    pipeline.poll()
    stored = store.load(pipeline.key)
    assert stored is not None
    assert stored.offset == len(complete)


def test_resume_seeks_past_what_was_already_read(tmp_path: Path, logfile: Path) -> None:
    store = CheckpointStore(directory=tmp_path / "ckpt")
    first = build(logfile, checkpoints=store)
    append(logfile, cri_full(TS, "one"))
    first.poll()
    append(logfile, cri_full(TS, "two"))
    second = build(logfile, checkpoints=store)
    assert second.resume() is True
    assert [r.message for r in second.poll()] == ["two"]


def test_resume_without_a_store_is_false(logfile: Path) -> None:
    assert build(logfile).resume() is False


def test_resume_with_no_stored_checkpoint_is_false(tmp_path: Path, logfile: Path) -> None:
    store = CheckpointStore(directory=tmp_path / "ckpt")
    assert build(logfile, checkpoints=store).resume() is False


def test_resume_refuses_a_checkpoint_from_a_different_file(
    tmp_path: Path, tree: SiteTree, logfile: Path
) -> None:
    store = CheckpointStore(directory=tmp_path / "ckpt")
    pipeline = build(logfile, checkpoints=store)
    append(logfile, cri_full(TS, "one"))
    pipeline.poll()
    tree.rotate(logfile)
    append(logfile, cri_full(TS, "after"))
    fresh = build(logfile, checkpoints=store)
    assert fresh.resume() is False
    assert [r.message for r in fresh.poll()] == ["after"]


def test_resume_refuses_an_offset_past_the_end(tmp_path: Path, logfile: Path) -> None:
    store = CheckpointStore(directory=tmp_path / "ckpt")
    pipeline = build(logfile, checkpoints=store)
    append(logfile, cri_full(TS, "one") + cri_full(TS, "two"))
    pipeline.poll()
    logfile.write_bytes(b"")
    fresh = build(logfile, checkpoints=store)
    assert fresh.resume() is False


def test_the_checkpoint_carries_the_clock_reading(tmp_path: Path, logfile: Path) -> None:
    store = CheckpointStore(directory=tmp_path / "ckpt")
    clock = ManualClock()
    clock.set_wall("2025-03-04T11:30:00Z")
    pipeline = UnitPipeline(
        site=SITE,
        info=parse_log_path(logfile.as_posix()),
        reader=CriReader(),
        checkpoints=store,
        clock=clock,
    )
    append(logfile, cri_full(TS, "one"))
    pipeline.poll()
    stored = store.load(pipeline.key)
    assert stored is not None
    assert stored.updated_at.isoformat().startswith("2025-03-04T11:30:00")
