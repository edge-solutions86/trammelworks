from __future__ import annotations

import json
from typing import Any

import pytest

from stanchion.errors import ShipError
from stanchion.ship.batch import (
    Batch,
    BatchLimits,
    BatchStats,
    cut_batches,
    record_size,
)

SITE = "US-TX-0417"


def record(index: int, message: str = "nozzle 1 lifted") -> dict[str, Any]:
    return {"unit": "pump-bridge", "n": index, "message": message}


def batches(count: int, **over: Any) -> list[Batch]:
    return list(cut_batches(SITE, [record(index) for index in range(count)], **over))


def test_an_empty_batch_is_refused() -> None:
    with pytest.raises(ShipError, match="cannot be empty"):
        Batch(site=SITE, records=())


def test_one_record_makes_one_batch() -> None:
    found = batches(1)
    assert len(found) == 1
    assert found[0].count == 1


def test_no_records_make_no_batches() -> None:
    assert batches(0) == []


def test_the_records_keep_their_order() -> None:
    found = batches(5, limits=BatchLimits(max_records=2))
    seen = [item["n"] for batch in found for item in batch.records]
    assert seen == [0, 1, 2, 3, 4]


def test_the_record_bound_cuts_the_batch() -> None:
    found = batches(5, limits=BatchLimits(max_records=2))
    assert [batch.count for batch in found] == [2, 2, 1]


def test_the_byte_bound_cuts_the_batch() -> None:
    one = record_size(record(0))
    found = batches(4, limits=BatchLimits(max_bytes=one * 2 + 1))
    assert all(batch.count <= 2 for batch in found)
    assert sum(batch.count for batch in found) == 4


def test_a_record_larger_than_the_bound_still_goes_alone() -> None:
    huge = record(0, message="x" * 5000)
    stats = BatchStats()
    found = list(cut_batches(SITE, [huge], limits=BatchLimits(max_bytes=100), stats=stats))
    assert len(found) == 1
    assert stats.oversized == 1


def test_an_oversized_record_does_not_drag_others_with_it() -> None:
    records = [record(0), record(1, message="x" * 5000), record(2)]
    found = list(cut_batches(SITE, records, limits=BatchLimits(max_bytes=200)))
    assert sum(batch.count for batch in found) == 3
    assert any(batch.count == 1 for batch in found)


def test_the_first_index_tracks_position_in_the_stream() -> None:
    found = batches(5, limits=BatchLimits(max_records=2))
    assert [batch.first_index for batch in found] == [0, 2, 4]
    assert [batch.last_index for batch in found] == [1, 3, 4]


def test_the_batch_id_is_stable_for_the_same_records() -> None:
    assert batches(3)[0].batch_id == batches(3)[0].batch_id


def test_the_batch_id_changes_with_the_records() -> None:
    left = Batch(site=SITE, records=(record(0),))
    right = Batch(site=SITE, records=(record(1),))
    assert left.batch_id != right.batch_id


def test_the_batch_id_changes_with_the_site() -> None:
    left = Batch(site=SITE, records=(record(0),))
    right = Batch(site="GB-LDN-0009", records=(record(0),))
    assert left.batch_id != right.batch_id


def test_the_batch_id_changes_with_the_source() -> None:
    left = Batch(site=SITE, records=(record(0),), source="a.spool")
    right = Batch(site=SITE, records=(record(0),), source="b.spool")
    assert left.batch_id != right.batch_id


def test_the_batch_id_does_not_depend_on_key_order() -> None:
    left = Batch(site=SITE, records=({"a": 1, "b": 2},))
    right = Batch(site=SITE, records=({"b": 2, "a": 1},))
    assert left.batch_id == right.batch_id


def test_the_batch_id_separates_records_so_a_shift_is_visible() -> None:
    left = Batch(site=SITE, records=({"m": "ab"}, {"m": "c"}))
    right = Batch(site=SITE, records=({"m": "a"}, {"m": "bc"}))
    assert left.batch_id != right.batch_id


def test_the_batch_id_is_hex_of_the_declared_width() -> None:
    found = batches(1)[0].batch_id
    assert len(found) == 24
    assert int(found, 16) >= 0


def test_a_retry_of_the_same_cut_carries_the_same_id() -> None:
    first = batches(4, limits=BatchLimits(max_records=2))
    second = batches(4, limits=BatchLimits(max_records=2))
    assert [b.batch_id for b in first] == [b.batch_id for b in second]


def test_the_payload_carries_the_id_and_count() -> None:
    payload = batches(2)[0].payload()
    assert payload["site"] == SITE
    assert payload["count"] == 2
    assert len(payload["records"]) == 2
    assert payload["batch_id"]


def test_the_encoded_form_is_json() -> None:
    decoded = json.loads(batches(2)[0].encode())
    assert decoded["count"] == 2


def test_the_wire_size_matches_the_encoded_length() -> None:
    batch = batches(2)[0]
    assert batch.wire_bytes == len(batch.encode())


def test_record_size_counts_the_separator() -> None:
    assert record_size({"a": 1}) == len(b'{"a":1}') + 1


def test_the_limits_reject_a_non_positive_bound() -> None:
    with pytest.raises(ShipError, match="max_bytes must be positive"):
        BatchLimits(max_bytes=0)
    with pytest.raises(ShipError, match="max_records must be positive"):
        BatchLimits(max_records=0)


def test_the_stats_count_what_was_cut() -> None:
    stats = BatchStats()
    list(cut_batches(SITE, [record(i) for i in range(5)], BatchLimits(max_records=2), stats=stats))
    assert stats.batches == 3
    assert stats.records == 5
    assert stats.bytes_planned > 0


def test_the_stats_remember_the_largest_batch() -> None:
    stats = BatchStats()
    list(cut_batches(SITE, [record(i) for i in range(5)], BatchLimits(max_records=2), stats=stats))
    assert stats.largest > 0


def test_the_source_is_carried_onto_the_payload() -> None:
    found = list(cut_batches(SITE, [record(0)], source="000000000041-payment.spool"))
    assert found[0].payload()["source"] == "000000000041-payment.spool"
