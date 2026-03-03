from __future__ import annotations

from pathlib import Path

import pytest

from stanchion.errors import SpoolError
from stanchion.ship.ack import AckLedger
from stanchion.ship.compact import (
    COMPACT_SUFFIX,
    CompactionResult,
    Compactor,
    clear_sidecars,
    plan_compaction,
    plan_one,
    prefix_bytes,
    rewrite,
)
from stanchion.spool.frame import encode_frame
from stanchion.spool.reader import read_segment
from stanchion.spool.segment import SegmentInfo


def body(index: int, size: int = 400) -> dict[str, object]:
    return {"seq": index, "message": f"{index}-" + "x" * size}


def write_segment(
    directory: Path, sequence: int, klass: str, count: int, size: int = 400
) -> SegmentInfo:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{sequence:012d}-{klass}.spool"
    with path.open("wb") as handle:
        for index in range(count):
            handle.write(encode_frame(body(index, size), compress=False))
    return SegmentInfo(path=path, sequence=sequence, klass=klass, size=path.stat().st_size)


@pytest.fixture
def segment(tmp_path: Path) -> SegmentInfo:
    return write_segment(tmp_path, 41, "payment", 20)


def ledger_for(segment: SegmentInfo, acked: int, total: int | None = None) -> AckLedger:
    built = AckLedger()
    built.mark(segment.name, 20 if total is None else total)
    if acked:
        built.acknowledge(segment.name, acked)
    return built


def sequences(path: Path) -> list[int]:
    return [int(frame.payload["seq"]) for frame in read_segment(path).frames]


def test_the_prefix_of_no_frames_is_no_bytes(segment: SegmentInfo) -> None:
    assert prefix_bytes(segment.path.read_bytes(), 0) == 0


def test_the_prefix_of_every_frame_is_the_whole_file(segment: SegmentInfo) -> None:
    data = segment.path.read_bytes()
    assert prefix_bytes(data, 20) == len(data)


def test_the_prefix_grows_with_the_count(segment: SegmentInfo) -> None:
    data = segment.path.read_bytes()
    assert prefix_bytes(data, 5) < prefix_bytes(data, 6)


def test_a_prefix_past_the_end_is_refused(segment: SegmentInfo) -> None:
    with pytest.raises(SpoolError, match="more frames than the segment holds"):
        prefix_bytes(segment.path.read_bytes(), 21)


def test_a_negative_prefix_is_refused(segment: SegmentInfo) -> None:
    with pytest.raises(SpoolError, match="negative"):
        prefix_bytes(segment.path.read_bytes(), -1)


def test_a_torn_segment_will_not_be_measured(segment: SegmentInfo) -> None:
    data = segment.path.read_bytes()[:-4]
    with pytest.raises(SpoolError, match="will not read whole"):
        prefix_bytes(data, 2)


def test_nothing_acknowledged_is_not_worth_compacting(segment: SegmentInfo) -> None:
    assert plan_one(segment, acked=0, total=20) is None


def test_everything_acknowledged_is_left_for_the_shipper(segment: SegmentInfo) -> None:
    assert plan_one(segment, acked=20, total=20) is None


def test_a_part_shipped_segment_is_worth_compacting(segment: SegmentInfo) -> None:
    plan = plan_one(segment, acked=15, total=20)
    assert plan is not None
    assert (plan.drop, plan.keep) == (15, 5)
    assert plan.reclaim > 0


def test_a_small_saving_is_not_worth_the_rewrite(segment: SegmentInfo) -> None:
    assert plan_one(segment, acked=1, total=20, min_reclaim=1024 * 1024) is None


def test_a_large_fraction_is_worth_it_even_when_small(tmp_path: Path) -> None:
    built = write_segment(tmp_path, 7, "payment", 4, size=10)
    plan = plan_one(built, acked=3, total=4, min_reclaim=1024 * 1024)
    assert plan is not None


def test_the_plan_reads_as_one_line(segment: SegmentInfo) -> None:
    plan = plan_one(segment, acked=10, total=20)
    assert plan is not None
    assert "drop 10 keep 10" in str(plan)


def test_the_plan_knows_its_fraction(segment: SegmentInfo) -> None:
    plan = plan_one(segment, acked=10, total=20)
    assert plan is not None
    assert 0.4 < plan.fraction < 0.6


def test_a_missing_segment_is_refused(tmp_path: Path) -> None:
    absent = SegmentInfo(path=tmp_path / "gone.spool", sequence=1, klass="payment", size=10)
    with pytest.raises(SpoolError, match="unreadable"):
        plan_one(absent, acked=1, total=2)


def test_planning_skips_segments_with_no_watermark(segment: SegmentInfo) -> None:
    assert plan_compaction([segment], AckLedger()).plans == ()


def test_planning_skips_a_pinned_segment(segment: SegmentInfo) -> None:
    ledger = ledger_for(segment, 15)
    assert plan_compaction([segment], ledger, pinned=frozenset({segment.path})).plans == ()


def test_planning_puts_the_biggest_saving_first(tmp_path: Path) -> None:
    small = write_segment(tmp_path, 1, "payment", 20)
    large = write_segment(tmp_path, 2, "payment", 20, size=4000)
    ledger = AckLedger()
    for item in (small, large):
        ledger.mark(item.name, 20)
        ledger.acknowledge(item.name, 10)
    survey = plan_compaction([small, large], ledger)
    assert [item.name for item in survey.plans] == [large.name, small.name]
    assert survey.reclaim > 0


def test_rewriting_drops_the_acknowledged_prefix(segment: SegmentInfo) -> None:
    rewrite(segment.path, 15)
    assert sequences(segment.path) == [15, 16, 17, 18, 19]


def test_rewriting_shrinks_the_file(segment: SegmentInfo) -> None:
    before = segment.path.stat().st_size
    rewrite(segment.path, 15)
    assert segment.path.stat().st_size < before


def test_rewriting_returns_the_new_size(segment: SegmentInfo) -> None:
    size = rewrite(segment.path, 15)
    assert size == segment.path.stat().st_size


def test_rewriting_keeps_the_frames_byte_identical(segment: SegmentInfo) -> None:
    data = segment.path.read_bytes()
    cut = prefix_bytes(data, 15)
    rewrite(segment.path, 15)
    assert segment.path.read_bytes() == data[cut:]


def test_rewriting_nothing_leaves_the_file_alone(segment: SegmentInfo) -> None:
    data = segment.path.read_bytes()
    rewrite(segment.path, 0)
    assert segment.path.read_bytes() == data


def test_rewriting_everything_leaves_an_empty_file(segment: SegmentInfo) -> None:
    rewrite(segment.path, 20)
    assert segment.path.read_bytes() == b""


def test_the_sidecar_does_not_survive_a_rewrite(segment: SegmentInfo) -> None:
    rewrite(segment.path, 5)
    assert not list(segment.path.parent.glob("*" + COMPACT_SUFFIX))


def test_a_torn_segment_is_not_rewritten(segment: SegmentInfo) -> None:
    segment.path.write_bytes(segment.path.read_bytes()[:-3])
    with pytest.raises(SpoolError, match="does not read"):
        rewrite(segment.path, 2)


def test_a_leftover_sidecar_is_cleared(tmp_path: Path, segment: SegmentInfo) -> None:
    stray = segment.path.with_name(segment.path.name + COMPACT_SUFFIX)
    stray.write_bytes(b"half a file")
    assert clear_sidecars(tmp_path) == [stray]
    assert not stray.exists()


def test_clearing_leaves_the_segments_alone(tmp_path: Path, segment: SegmentInfo) -> None:
    clear_sidecars(tmp_path)
    assert segment.path.exists()


def test_clearing_a_directory_that_is_not_there_is_harmless(tmp_path: Path) -> None:
    assert clear_sidecars(tmp_path / "absent") == []


def test_a_pass_compacts_and_reports(tmp_path: Path, segment: SegmentInfo) -> None:
    ledger = ledger_for(segment, 15)
    result = Compactor(directory=tmp_path, ledger=ledger).run([segment])
    assert result.segments == 1
    assert result.frames_dropped == 15
    assert result.reclaimed > 0


def test_a_pass_moves_the_watermark_back_to_zero(tmp_path: Path, segment: SegmentInfo) -> None:
    ledger = ledger_for(segment, 15)
    Compactor(directory=tmp_path, ledger=ledger).run([segment])
    mark = ledger.marks[segment.name]
    assert (mark.total, mark.acked) == (5, 0)


def test_what_is_left_is_what_was_not_acknowledged(tmp_path: Path, segment: SegmentInfo) -> None:
    ledger = ledger_for(segment, 15)
    Compactor(directory=tmp_path, ledger=ledger).run([segment])
    assert sequences(segment.path) == [15, 16, 17, 18, 19]


def test_a_pass_with_nothing_to_do_is_idle(tmp_path: Path, segment: SegmentInfo) -> None:
    ledger = ledger_for(segment, 0)
    assert Compactor(directory=tmp_path, ledger=ledger).run([segment]).idle


def test_an_unreadable_segment_is_named_not_raised(tmp_path: Path, segment: SegmentInfo) -> None:
    ledger = ledger_for(segment, 15)
    compactor = Compactor(directory=tmp_path, ledger=ledger)
    segment.path.write_bytes(segment.path.read_bytes()[:-3])
    result = compactor.run([segment])
    assert result.failed == (segment.name,)
    assert result.segments == 0


def test_a_failure_leaves_the_watermark_alone(tmp_path: Path, segment: SegmentInfo) -> None:
    ledger = ledger_for(segment, 15)
    compactor = Compactor(directory=tmp_path, ledger=ledger)
    segment.path.write_bytes(segment.path.read_bytes()[:-3])
    compactor.run([segment])
    assert ledger.marks[segment.name].acked == 15


def test_the_metrics_count_what_was_freed(tmp_path: Path, segment: SegmentInfo) -> None:
    ledger = ledger_for(segment, 15)
    compactor = Compactor(directory=tmp_path, ledger=ledger)
    compactor.run([segment])
    assert compactor.metrics.snapshot()["spool_compacted_segments"] == 1
    assert compactor.metrics.snapshot()["spool_compacted_bytes"] > 0


def test_a_result_reads_as_one_line() -> None:
    result = CompactionResult(segments=2, frames_dropped=30, reclaimed=4096)
    assert str(result) == "compacted 2 segments, dropped 30 records, freed 4096 bytes"


def test_a_result_with_failures_says_so() -> None:
    assert "1 failed" in str(CompactionResult(failed=("000000000001-payment.spool",)))


def test_a_compacted_segment_can_be_shipped_from_the_start(
    tmp_path: Path, segment: SegmentInfo
) -> None:
    ledger = ledger_for(segment, 15)
    Compactor(directory=tmp_path, ledger=ledger).run([segment])
    found = read_segment(segment.path)
    mark = ledger.mark(segment.name, len(found.frames))
    assert mark.outstanding == 5


def test_a_segment_that_will_not_read_is_surveyed_not_raised(segment: SegmentInfo) -> None:
    ledger = ledger_for(segment, 15)
    segment.path.write_bytes(segment.path.read_bytes()[:-3])
    survey = plan_compaction([segment], ledger)
    assert survey.unreadable == (segment.name,)
    assert survey.plans == ()
