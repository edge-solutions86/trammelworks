from __future__ import annotations

from pathlib import Path

import pytest

from stanchion.errors import SpoolError
from stanchion.spool.budget import Budget, Usage, measure, report
from stanchion.spool.segment import SegmentInfo


def segment(sequence: int, klass: str, size: int) -> SegmentInfo:
    return SegmentInfo(path=Path(f"{sequence}.spool"), sequence=sequence, klass=klass, size=size)


def test_measuring_nothing_gives_zero() -> None:
    usage = measure([])
    assert usage.used == 0
    assert usage.segments == 0
    assert usage.by_class == {}


def test_measuring_adds_the_sizes_up() -> None:
    usage = measure([segment(0, "payment", 100), segment(1, "chatter", 50)])
    assert usage.used == 150
    assert usage.segments == 2


def test_measuring_splits_by_class() -> None:
    usage = measure([segment(0, "payment", 100), segment(1, "payment", 40)])
    assert usage.of_class("payment") == 140
    assert usage.of_class("chatter") == 0


def test_the_open_segment_counts_towards_the_total() -> None:
    usage = measure([segment(0, "payment", 100)], open_bytes=25)
    assert usage.used == 125
    assert usage.sealed_bytes == 100
    assert usage.open_bytes == 25


def test_a_non_positive_budget_is_refused() -> None:
    with pytest.raises(SpoolError, match="budget must be positive"):
        Budget(limit_bytes=0)


@pytest.mark.parametrize("ratio", [-0.1, 1.0, 2.0])
def test_a_reserve_outside_the_range_is_refused(ratio: float) -> None:
    with pytest.raises(SpoolError, match=r"reserve ratio must be in"):
        Budget(limit_bytes=1000, reserve_ratio=ratio)


def test_the_reserve_is_taken_off_the_top() -> None:
    budget = Budget(limit_bytes=1000, reserve_ratio=0.1)
    assert budget.reserve_bytes == 100
    assert budget.usable_bytes == 900


def test_a_zero_reserve_makes_everything_usable() -> None:
    assert Budget(limit_bytes=1000, reserve_ratio=0.0).usable_bytes == 1000


def test_free_counts_down_from_usable() -> None:
    budget = Budget(limit_bytes=1000, reserve_ratio=0.1)
    assert budget.free(measure([segment(0, "payment", 400)])) == 500


def test_free_never_goes_negative() -> None:
    budget = Budget(limit_bytes=1000, reserve_ratio=0.1)
    assert budget.free(measure([segment(0, "payment", 5000)])) == 0


def test_a_write_that_fits_is_admitted() -> None:
    budget = Budget(limit_bytes=1000, reserve_ratio=0.1)
    assert budget.admits(measure([segment(0, "payment", 800)]), 100) is True


def test_a_write_that_lands_exactly_on_usable_is_admitted() -> None:
    budget = Budget(limit_bytes=1000, reserve_ratio=0.1)
    assert budget.admits(measure([segment(0, "payment", 800)]), 100) is True
    assert budget.admits(measure([segment(0, "payment", 900)]), 0) is True


def test_a_write_that_would_cross_usable_is_not_admitted() -> None:
    budget = Budget(limit_bytes=1000, reserve_ratio=0.1)
    assert budget.admits(measure([segment(0, "payment", 850)]), 100) is False


def test_a_negative_size_is_refused() -> None:
    with pytest.raises(SpoolError, match="negative size"):
        Budget(limit_bytes=1000).admits(Usage(), -1)


def test_over_by_is_zero_when_inside_the_budget() -> None:
    budget = Budget(limit_bytes=1000, reserve_ratio=0.1)
    assert budget.over_by(measure([segment(0, "payment", 500)])) == 0


def test_over_by_measures_what_must_be_freed() -> None:
    budget = Budget(limit_bytes=1000, reserve_ratio=0.1)
    assert budget.over_by(measure([segment(0, "payment", 950)])) == 50


def test_the_reserve_still_covers_a_write_past_usable() -> None:
    budget = Budget(limit_bytes=1000, reserve_ratio=0.1)
    usage = measure([segment(0, "payment", 900)])
    assert budget.admits(usage, 50) is False
    assert budget.would_exceed_hard_limit(usage, 50) is False


def test_a_write_past_the_hard_limit_is_named() -> None:
    budget = Budget(limit_bytes=1000, reserve_ratio=0.1)
    usage = measure([segment(0, "payment", 900)])
    assert budget.would_exceed_hard_limit(usage, 200) is True


def test_the_fill_ratio_is_measured_against_the_whole_budget() -> None:
    budget = Budget(limit_bytes=1000, reserve_ratio=0.1)
    assert budget.fill_ratio(measure([segment(0, "payment", 500)])) == 0.5


def test_the_report_carries_the_numbers() -> None:
    budget = Budget(limit_bytes=1000, reserve_ratio=0.1)
    built = report(budget, measure([segment(0, "payment", 250)]))
    assert built.used == 250
    assert built.limit == 1000
    assert built.reserve == 100
    assert built.segments == 1


def test_the_report_percentage_is_of_the_budget() -> None:
    budget = Budget(limit_bytes=1000, reserve_ratio=0.1)
    assert report(budget, measure([segment(0, "payment", 250)])).percent == 25.0


def test_the_report_reads_as_one_line() -> None:
    budget = Budget(limit_bytes=1000, reserve_ratio=0.1)
    line = str(report(budget, measure([segment(0, "payment", 250)])))
    assert line == "spool 250/1000 bytes (25.0%), 1 segments"


def test_the_report_keeps_the_class_breakdown() -> None:
    budget = Budget(limit_bytes=1000)
    built = report(budget, measure([segment(0, "payment", 100), segment(1, "chatter", 40)]))
    assert built.by_class == {"payment": 100, "chatter": 40}
