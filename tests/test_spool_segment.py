from __future__ import annotations

from pathlib import Path

import pytest

from stanchion.errors import SpoolError
from stanchion.spool.frame import encode_frame
from stanchion.spool.segment import (
    SegmentWriter,
    check_class,
    list_segments,
    next_sequence,
    parse_segment_name,
    segment_name,
)

FRAME = encode_frame({"message": "nozzle 1 lifted"})


@pytest.fixture
def spool(tmp_path: Path) -> Path:
    return tmp_path / "spool"


def writer(spool: Path, sequence: int = 0, klass: str = "payment", **over: object) -> SegmentWriter:
    return SegmentWriter(directory=spool, sequence=sequence, klass=klass, **over)  # type: ignore[arg-type]


@pytest.mark.parametrize("klass", ["payment", "tank_gauge", "a1"])
def test_check_class_accepts_a_sane_name(klass: str) -> None:
    assert check_class(klass) == klass


@pytest.mark.parametrize("klass", ["Payment", "1st", "with-dash", "", "with space"])
def test_check_class_rejects_anything_else(klass: str) -> None:
    with pytest.raises(SpoolError, match="malformed priority class"):
        check_class(klass)


def test_a_name_is_zero_padded_so_it_sorts() -> None:
    assert segment_name(41, "payment") == "000000000041-payment.spool"


def test_names_sort_lexically_in_sequence_order() -> None:
    names = [segment_name(number, "payment") for number in (2, 10, 1)]
    assert sorted(names) == [segment_name(n, "payment") for n in (1, 2, 10)]


def test_a_negative_sequence_is_refused() -> None:
    with pytest.raises(SpoolError, match="negative segment sequence"):
        segment_name(-1, "payment")


def test_a_name_round_trips() -> None:
    assert parse_segment_name(segment_name(41, "payment")) == (41, "payment")


@pytest.mark.parametrize("name", ["41-payment.spool", "000000000041-payment.part", "notes.txt", ""])
def test_an_unrelated_name_is_not_a_segment(name: str) -> None:
    assert parse_segment_name(name) is None


def test_listing_an_absent_directory_is_empty(spool: Path) -> None:
    assert list_segments(spool) == []


def test_a_sealed_segment_is_listed(spool: Path) -> None:
    handle = writer(spool)
    handle.append(FRAME)
    handle.seal()
    found = list_segments(spool)
    assert [item.sequence for item in found] == [0]
    assert found[0].size == len(FRAME)


def test_segments_are_listed_oldest_first(spool: Path) -> None:
    for sequence in (2, 0, 1):
        handle = writer(spool, sequence=sequence)
        handle.append(FRAME)
        handle.seal()
    assert [item.sequence for item in list_segments(spool)] == [0, 1, 2]


def test_an_unsealed_segment_is_not_listed(spool: Path) -> None:
    handle = writer(spool)
    handle.append(FRAME)
    handle.flush()
    assert list_segments(spool) == []


def test_an_unrelated_file_is_not_listed(spool: Path) -> None:
    spool.mkdir(parents=True)
    (spool / "notes.txt").write_text("hello", encoding="utf-8")
    assert list_segments(spool) == []


def test_the_class_is_read_back_from_the_name(spool: Path) -> None:
    handle = writer(spool, klass="tank_gauge")
    handle.append(FRAME)
    handle.seal()
    assert list_segments(spool)[0].klass == "tank_gauge"


def test_the_first_sequence_on_an_empty_directory_is_zero(spool: Path) -> None:
    assert next_sequence(spool) == 0


def test_the_next_sequence_is_one_past_the_highest(spool: Path) -> None:
    handle = writer(spool, sequence=7)
    handle.append(FRAME)
    handle.seal()
    assert next_sequence(spool) == 8


def test_an_unsealed_segment_still_reserves_its_number(spool: Path) -> None:
    handle = writer(spool, sequence=7)
    handle.append(FRAME)
    handle.flush()
    assert next_sequence(spool) == 8


def test_writing_creates_the_directory(spool: Path) -> None:
    handle = writer(spool)
    handle.append(FRAME)
    assert spool.is_dir()


def test_the_write_goes_to_a_part_file(spool: Path) -> None:
    handle = writer(spool)
    handle.append(FRAME)
    assert handle.partial_path.exists()
    assert not handle.sealed_path.exists()


def test_sealing_renames_into_place(spool: Path) -> None:
    handle = writer(spool)
    handle.append(FRAME)
    sealed = handle.seal()
    assert sealed is not None
    assert sealed.path.exists()
    assert not handle.partial_path.exists()


def test_the_offset_returned_is_where_the_frame_landed(spool: Path) -> None:
    handle = writer(spool)
    assert handle.append(FRAME) == 0
    assert handle.append(FRAME) == len(FRAME)


def test_the_size_follows_what_was_written(spool: Path) -> None:
    handle = writer(spool)
    handle.append(FRAME)
    handle.append(FRAME)
    assert handle.size == 2 * len(FRAME)
    assert handle.frames == 2


def test_an_empty_segment_is_removed_rather_than_sealed(spool: Path) -> None:
    handle = writer(spool)
    handle._stream()
    assert handle.seal() is None
    assert list_segments(spool) == []


def test_sealing_a_segment_that_was_never_opened_is_a_no_op(spool: Path) -> None:
    assert writer(spool).seal() is None


def test_full_follows_the_size_cap(spool: Path) -> None:
    handle = writer(spool, max_bytes=len(FRAME))
    assert handle.full is False
    handle.append(FRAME)
    assert handle.full is True


def test_would_overflow_asks_before_writing(spool: Path) -> None:
    handle = writer(spool, max_bytes=len(FRAME) + 1)
    assert handle.would_overflow(len(FRAME)) is False
    handle.append(FRAME)
    assert handle.would_overflow(len(FRAME)) is True


def test_a_non_positive_cap_is_refused(spool: Path) -> None:
    with pytest.raises(SpoolError, match="max_bytes must be positive"):
        writer(spool, max_bytes=0)


def test_a_negative_sync_interval_is_refused(spool: Path) -> None:
    with pytest.raises(SpoolError, match="sync_every cannot be negative"):
        writer(spool, sync_every=-1)


def test_syncing_every_write_leaves_the_bytes_on_disk(spool: Path) -> None:
    handle = writer(spool, sync_every=1)
    handle.append(FRAME)
    assert handle.partial_path.stat().st_size == len(FRAME)


def test_the_bytes_are_readable_after_sealing(spool: Path) -> None:
    handle = writer(spool)
    handle.append(FRAME)
    sealed = handle.seal()
    assert sealed is not None
    assert sealed.path.read_bytes() == FRAME


def test_reopening_the_same_sequence_appends(spool: Path) -> None:
    first = writer(spool)
    first.append(FRAME)
    first.flush()
    first._handle.close()  # type: ignore[union-attr]
    first._handle = None
    second = writer(spool)
    second.append(FRAME)
    assert second.size == 2 * len(FRAME)


def test_open_reports_whether_a_handle_is_held(spool: Path) -> None:
    handle = writer(spool)
    assert handle.open is False
    handle.append(FRAME)
    assert handle.open is True
    handle.seal()
    assert handle.open is False
