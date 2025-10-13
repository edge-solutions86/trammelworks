from __future__ import annotations

import json
from pathlib import Path

import pytest

from stanchion.errors import ShipError
from stanchion.ship.ack import AckLedger, AckStore, Watermark

SEGMENT = "000000000041-payment.spool"
OTHER = "000000000042-chatter.spool"


def test_a_fresh_watermark_is_at_zero() -> None:
    mark = Watermark(segment=SEGMENT, total=10)
    assert mark.acked == 0
    assert mark.outstanding == 10
    assert mark.done is False


def test_a_watermark_at_the_end_is_done() -> None:
    assert Watermark(segment=SEGMENT, total=10, acked=10).done is True


def test_an_empty_segment_is_done_immediately() -> None:
    assert Watermark(segment=SEGMENT, total=0).done is True


def test_a_watermark_past_the_end_is_refused() -> None:
    with pytest.raises(ShipError, match="outside the segment"):
        Watermark(segment=SEGMENT, total=5, acked=6)


def test_a_negative_total_is_refused() -> None:
    with pytest.raises(ShipError, match="negative number of records"):
        Watermark(segment=SEGMENT, total=-1)


def test_advancing_moves_the_watermark() -> None:
    mark = Watermark(segment=SEGMENT, total=10)
    assert mark.advance(4) == 4
    assert mark.advance(6) == 10
    assert mark.done is True


def test_advancing_by_zero_is_allowed() -> None:
    mark = Watermark(segment=SEGMENT, total=10)
    assert mark.advance(0) == 0


def test_advancing_by_a_negative_count_is_refused() -> None:
    with pytest.raises(ShipError, match="negative count"):
        Watermark(segment=SEGMENT, total=10).advance(-1)


def test_advancing_past_the_end_is_refused() -> None:
    mark = Watermark(segment=SEGMENT, total=10)
    mark.advance(8)
    with pytest.raises(ShipError, match="runs past the end"):
        mark.advance(5)


def test_a_watermark_round_trips_through_its_mapping() -> None:
    mark = Watermark(segment=SEGMENT, total=10, acked=3, attempts=2, failed=1)
    assert Watermark.from_dict(mark.as_dict()) == mark


def test_a_watermark_missing_a_field_is_refused() -> None:
    with pytest.raises(ShipError, match="missing a field"):
        Watermark.from_dict({"segment": SEGMENT})


def test_the_ledger_starts_a_watermark_on_first_sight() -> None:
    ledger = AckLedger()
    mark = ledger.mark(SEGMENT, 10)
    assert mark.total == 10
    assert ledger.mark(SEGMENT, 10) is mark


def test_a_segment_that_changes_size_is_refused() -> None:
    ledger = AckLedger()
    ledger.mark(SEGMENT, 10)
    with pytest.raises(ShipError, match="changed size under the watermark"):
        ledger.mark(SEGMENT, 11)


def test_acknowledging_advances_the_right_segment() -> None:
    ledger = AckLedger()
    ledger.mark(SEGMENT, 10)
    ledger.mark(OTHER, 4)
    ledger.acknowledge(SEGMENT, 3)
    assert ledger.marks[SEGMENT].acked == 3
    assert ledger.marks[OTHER].acked == 0


def test_acknowledging_an_unknown_segment_is_refused() -> None:
    with pytest.raises(ShipError, match="nobody is shipping"):
        AckLedger().acknowledge(SEGMENT, 1)


def test_done_and_pending_split_the_segments() -> None:
    ledger = AckLedger()
    ledger.mark(SEGMENT, 2)
    ledger.mark(OTHER, 2)
    ledger.acknowledge(SEGMENT, 2)
    assert ledger.done_segments() == (SEGMENT,)
    assert ledger.pending_segments() == (OTHER,)


def test_the_outstanding_total_adds_up() -> None:
    ledger = AckLedger()
    ledger.mark(SEGMENT, 10)
    ledger.mark(OTHER, 4)
    ledger.acknowledge(SEGMENT, 6)
    assert ledger.outstanding == 8


def test_forgetting_removes_a_watermark() -> None:
    ledger = AckLedger()
    ledger.mark(SEGMENT, 4)
    assert ledger.forget(SEGMENT) is True
    assert ledger.forget(SEGMENT) is False


def test_sweeping_drops_segments_that_are_gone() -> None:
    ledger = AckLedger()
    ledger.mark(SEGMENT, 4)
    ledger.mark(OTHER, 4)
    assert ledger.sweep({SEGMENT}) == 1
    assert ledger.pending_segments() == (SEGMENT,)


def test_sweeping_with_everything_live_drops_nothing() -> None:
    ledger = AckLedger()
    ledger.mark(SEGMENT, 4)
    assert ledger.sweep({SEGMENT}) == 0


@pytest.fixture
def store(tmp_path: Path) -> AckStore:
    return AckStore(directory=tmp_path / "state")


def test_loading_from_nothing_gives_an_empty_ledger(store: AckStore) -> None:
    assert store.load().marks == {}


def test_a_saved_ledger_comes_back(store: AckStore) -> None:
    ledger = AckLedger()
    ledger.mark(SEGMENT, 10)
    ledger.acknowledge(SEGMENT, 4)
    store.save(ledger)
    restored = store.load()
    assert restored.marks[SEGMENT].acked == 4
    assert restored.marks[SEGMENT].total == 10


def test_save_creates_the_directory(tmp_path: Path) -> None:
    store = AckStore(directory=tmp_path / "deep" / "state")
    store.save(AckLedger())
    assert store.path.exists()


def test_save_leaves_no_temporary_file(store: AckStore) -> None:
    store.save(AckLedger())
    assert [item.name for item in store.directory.iterdir()] == ["acks.json"]


def test_a_corrupt_ledger_reads_as_empty(store: AckStore) -> None:
    store.save(AckLedger())
    store.path.write_text("{broken", encoding="utf-8")
    assert store.load().marks == {}
    assert store.corrupt == 1


def test_a_ledger_from_another_version_reads_as_empty(store: AckStore) -> None:
    store.directory.mkdir(parents=True, exist_ok=True)
    store.path.write_text(json.dumps({"version": 99, "marks": []}), encoding="utf-8")
    assert store.load().marks == {}
    assert store.corrupt == 1


def test_a_ledger_with_a_broken_mark_reads_as_empty(store: AckStore) -> None:
    store.directory.mkdir(parents=True, exist_ok=True)
    payload = {"version": 1, "marks": [{"segment": SEGMENT, "total": 4, "acked": 9}]}
    store.path.write_text(json.dumps(payload), encoding="utf-8")
    assert store.load().marks == {}
    assert store.corrupt == 1


def test_the_ledger_survives_a_restart_mid_segment(store: AckStore) -> None:
    ledger = AckLedger()
    ledger.mark(SEGMENT, 10)
    ledger.acknowledge(SEGMENT, 7)
    store.save(ledger)
    resumed = store.load()
    assert resumed.marks[SEGMENT].outstanding == 3
    assert resumed.pending_segments() == (SEGMENT,)
