from __future__ import annotations

from pathlib import Path

import pytest

from stanchion.errors import SpoolError
from stanchion.spool.evict import apply_eviction, evictable_bytes, plan_eviction
from stanchion.spool.priority import PriorityTable
from stanchion.spool.segment import SegmentInfo

TABLE = PriorityTable()


def segment(sequence: int, klass: str, size: int, root: Path | None = None) -> SegmentInfo:
    base = root if root is not None else Path("/spool")
    return SegmentInfo(
        path=base / f"{sequence:012d}-{klass}.spool", sequence=sequence, klass=klass, size=size
    )


def on_disk(root: Path, sequence: int, klass: str, size: int) -> SegmentInfo:
    root.mkdir(parents=True, exist_ok=True)
    info = segment(sequence, klass, size, root=root)
    info.path.write_bytes(b"x" * size)
    return info


def test_needing_nothing_plans_nothing() -> None:
    plan = plan_eviction([segment(0, "chatter", 100)], TABLE, 0)
    assert plan.empty is True
    assert plan.sufficient is True


def test_a_negative_need_is_refused() -> None:
    with pytest.raises(SpoolError, match="negative number of bytes"):
        plan_eviction([], TABLE, -1)


def test_the_lowest_class_goes_first() -> None:
    segments = [segment(0, "standard", 100), segment(1, "chatter", 100)]
    plan = plan_eviction(segments, TABLE, 50)
    assert [item.klass for item in plan.victims] == ["chatter"]


def test_the_oldest_goes_first_inside_a_class() -> None:
    segments = [segment(5, "chatter", 100), segment(2, "chatter", 100)]
    plan = plan_eviction(segments, TABLE, 50)
    assert [item.sequence for item in plan.victims] == [2]


def test_a_protected_class_is_never_chosen() -> None:
    segments = [segment(0, "payment", 1000)]
    plan = plan_eviction(segments, TABLE, 500)
    assert plan.empty is True
    assert plan.sufficient is False
    assert plan.protected_bytes == 1000


def test_a_pinned_segment_is_never_chosen() -> None:
    victim = segment(0, "chatter", 1000)
    plan = plan_eviction([victim], TABLE, 500, pinned=frozenset({victim.path}))
    assert plan.empty is True
    assert plan.pinned_bytes == 1000


def test_enough_segments_are_taken_to_cover_the_need() -> None:
    segments = [segment(index, "chatter", 100) for index in range(5)]
    plan = plan_eviction(segments, TABLE, 250)
    assert len(plan.victims) == 3
    assert plan.freed == 300
    assert plan.sufficient is True


def test_no_more_are_taken_than_needed() -> None:
    segments = [segment(index, "chatter", 100) for index in range(5)]
    assert len(plan_eviction(segments, TABLE, 100).victims) == 1


def test_a_class_is_emptied_before_the_next_one_is_touched() -> None:
    segments = [segment(0, "chatter", 100), segment(1, "standard", 100)]
    plan = plan_eviction(segments, TABLE, 150)
    assert [item.klass for item in plan.victims] == ["chatter", "standard"]


def test_the_shortfall_is_reported_when_there_is_not_enough() -> None:
    plan = plan_eviction([segment(0, "chatter", 100)], TABLE, 400)
    assert plan.sufficient is False
    assert plan.shortfall == 300


def test_a_spool_full_of_protected_records_cannot_be_freed() -> None:
    segments = [segment(index, "payment", 100) for index in range(10)]
    plan = plan_eviction(segments, TABLE, 500)
    assert plan.sufficient is False
    assert plan.freed == 0


def test_protected_and_pinned_bytes_are_reported_even_at_zero_need() -> None:
    segments = [segment(0, "payment", 100), segment(1, "chatter", 40)]
    plan = plan_eviction(segments, TABLE, 0, pinned=frozenset({segments[1].path}))
    assert plan.protected_bytes == 100
    assert plan.pinned_bytes == 40


def test_evictable_bytes_excludes_protected_and_pinned() -> None:
    pinned = segment(2, "chatter", 40)
    segments = [segment(0, "payment", 100), segment(1, "chatter", 30), pinned]
    assert evictable_bytes(segments, TABLE, frozenset({pinned.path})) == 30


def test_evictable_bytes_of_an_empty_spool_is_zero() -> None:
    assert evictable_bytes([], TABLE) == 0


def test_applying_a_plan_deletes_the_files(tmp_path: Path) -> None:
    victim = on_disk(tmp_path, 0, "chatter", 100)
    plan = plan_eviction([victim], TABLE, 50)
    result = apply_eviction(plan)
    assert result.count == 1
    assert result.freed == 100
    assert not victim.path.exists()


def test_applying_an_empty_plan_does_nothing(tmp_path: Path) -> None:
    keeper = on_disk(tmp_path, 0, "chatter", 100)
    result = apply_eviction(plan_eviction([keeper], TABLE, 0))
    assert result.count == 0
    assert keeper.path.exists()


def test_a_segment_already_gone_still_counts_as_freed(tmp_path: Path) -> None:
    victim = on_disk(tmp_path, 0, "chatter", 100)
    plan = plan_eviction([victim], TABLE, 50)
    victim.path.unlink()
    result = apply_eviction(plan)
    assert result.freed == 100
    assert result.failed == []


def test_several_segments_are_deleted_in_order(tmp_path: Path) -> None:
    segments = [on_disk(tmp_path, index, "chatter", 100) for index in range(3)]
    result = apply_eviction(plan_eviction(segments, TABLE, 250))
    assert [item.sequence for item in result.deleted] == [0, 1, 2]
    assert not any(item.path.exists() for item in segments)


def test_a_directory_in_place_of_a_segment_is_reported_not_raised(tmp_path: Path) -> None:
    root = tmp_path / "spool"
    root.mkdir()
    info = segment(0, "chatter", 100, root=root)
    info.path.mkdir()
    result = apply_eviction(plan_eviction([info], TABLE, 50))
    assert result.count == 0
    assert len(result.failed) == 1
