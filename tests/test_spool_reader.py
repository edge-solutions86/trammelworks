from __future__ import annotations

from pathlib import Path

import pytest

from stanchion.errors import SpoolError
from stanchion.spool.frame import HEADER_SIZE, encode_frame
from stanchion.spool.reader import (
    QUARANTINE_SUFFIX,
    quarantine,
    read_segment,
    recover_directory,
    recover_partial,
)
from stanchion.spool.segment import list_segments, segment_name

ONE = encode_frame({"n": 1}, compress=False)
TWO = encode_frame({"n": 2}, compress=False)


@pytest.fixture
def spool(tmp_path: Path) -> Path:
    target = tmp_path / "spool"
    target.mkdir(parents=True)
    return target


def sealed(spool: Path, data: bytes, sequence: int = 0) -> Path:
    path = spool / segment_name(sequence, "payment")
    path.write_bytes(data)
    return path


def partial(spool: Path, data: bytes, sequence: int = 0) -> Path:
    path = spool / (segment_name(sequence, "payment") + ".part")
    path.write_bytes(data)
    return path


def test_a_whole_segment_reads_back(spool: Path) -> None:
    found = read_segment(sealed(spool, ONE + TWO))
    assert [frame.payload["n"] for frame in found.frames] == [1, 2]
    assert found.whole is True


def test_the_payloads_come_out_in_order(spool: Path) -> None:
    assert read_segment(sealed(spool, ONE + TWO)).payloads == [{"n": 1}, {"n": 2}]


def test_an_empty_segment_reads_as_nothing(spool: Path) -> None:
    found = read_segment(sealed(spool, b""))
    assert found.frames == []
    assert found.whole is True


def test_a_torn_tail_is_reported(spool: Path) -> None:
    found = read_segment(sealed(spool, ONE + TWO[:5]))
    assert len(found.frames) == 1
    assert found.whole is False
    assert found.consumed == len(ONE)


def test_a_corrupt_frame_stops_the_read(spool: Path) -> None:
    broken = bytearray(TWO)
    broken[HEADER_SIZE] ^= 0xFF
    found = read_segment(sealed(spool, ONE + bytes(broken)))
    assert len(found.frames) == 1
    assert found.note is not None


def test_an_unreadable_path_is_refused(spool: Path) -> None:
    with pytest.raises(SpoolError, match="segment unreadable"):
        read_segment(spool / "absent.spool")


def test_recovering_a_whole_partial_seals_it(spool: Path) -> None:
    path = partial(spool, ONE + TWO)
    found = recover_partial(path)
    assert found.sealed is not None
    assert found.frames == 2
    assert found.dropped_bytes == 0
    assert not path.exists()


def test_the_sealed_segment_is_then_listed(spool: Path) -> None:
    recover_partial(partial(spool, ONE))
    assert [item.sequence for item in list_segments(spool)] == [0]


def test_a_torn_partial_is_truncated_to_the_last_good_frame(spool: Path) -> None:
    path = partial(spool, ONE + TWO[:5])
    found = recover_partial(path)
    assert found.frames == 1
    assert found.dropped_bytes == 5
    assert found.sealed is not None
    assert found.sealed.path.read_bytes() == ONE


def test_the_records_before_the_tear_survive(spool: Path) -> None:
    recover_partial(partial(spool, ONE + TWO[:5]))
    kept = read_segment(list_segments(spool)[0].path)
    assert kept.payloads == [{"n": 1}]
    assert kept.whole is True


def test_a_partial_with_no_whole_frame_is_removed(spool: Path) -> None:
    path = partial(spool, ONE[:4])
    found = recover_partial(path)
    assert found.removed is True
    assert found.sealed is None
    assert not path.exists()
    assert list_segments(spool) == []


def test_an_empty_partial_is_removed(spool: Path) -> None:
    assert recover_partial(partial(spool, b"")).removed is True


def test_recovering_a_file_that_is_not_a_partial_is_refused(spool: Path) -> None:
    with pytest.raises(SpoolError, match="not a partial segment"):
        recover_partial(sealed(spool, ONE))


def test_a_partial_with_a_junk_name_is_refused(spool: Path) -> None:
    path = spool / "notes.part"
    path.write_bytes(ONE)
    with pytest.raises(SpoolError, match="unreadable name"):
        recover_partial(path)


def test_recovering_a_directory_handles_every_partial(spool: Path) -> None:
    partial(spool, ONE, sequence=0)
    partial(spool, ONE + TWO, sequence=1)
    found = recover_directory(spool)
    assert len(found) == 2
    assert [item.sequence for item in list_segments(spool)] == [0, 1]


def test_recovering_a_directory_leaves_sealed_segments_alone(spool: Path) -> None:
    sealed(spool, ONE, sequence=0)
    partial(spool, TWO, sequence=1)
    assert len(recover_directory(spool)) == 1
    assert len(list_segments(spool)) == 2


def test_recovering_an_absent_directory_is_empty(tmp_path: Path) -> None:
    assert recover_directory(tmp_path / "nope") == []


def test_recovering_a_clean_directory_does_nothing(spool: Path) -> None:
    sealed(spool, ONE)
    assert recover_directory(spool) == []


def test_quarantine_moves_the_file_aside(spool: Path) -> None:
    path = sealed(spool, ONE)
    moved = quarantine(path)
    assert moved.name.endswith(QUARANTINE_SUFFIX)
    assert not path.exists()
    assert moved.read_bytes() == ONE


def test_a_quarantined_file_is_no_longer_a_segment(spool: Path) -> None:
    quarantine(sealed(spool, ONE))
    assert list_segments(spool) == []


def test_quarantining_twice_replaces_the_first(spool: Path) -> None:
    quarantine(sealed(spool, ONE))
    quarantine(sealed(spool, TWO))
    moved = list(spool.glob("*" + QUARANTINE_SUFFIX))
    assert len(moved) == 1
    assert moved[0].read_bytes() == TWO
