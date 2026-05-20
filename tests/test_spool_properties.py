"""Properties the spool has to hold for any input, not just the examples.

The example based tests say what happens to a record. These say what is true of
every record, and they exist because the spool is the piece where being wrong is
silent: a frame that decodes to something slightly different still decodes, and
the site ships it without complaint.

Three properties carry most of the weight.

* **A frame round trips.** Whatever went in comes back out, byte identical after
  decoding, for any payload the records module can produce.
* **A torn tail loses only the torn frame.** Truncating a segment anywhere must
  leave every whole frame before the cut readable, because that is exactly what
  a power cut does and the recovery path depends on it.
* **The budget is never over.** However the writes fall, a spool that accepted
  them is inside its limit or refused the one that would not fit.
"""

from __future__ import annotations

import tempfile
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

import pytest
from hypothesis import assume, given, settings
from hypothesis import strategies as st

from stanchion.errors import SpoolError
from stanchion.records import LogRecord, Stream
from stanchion.spool.budget import Budget, measure
from stanchion.spool.frame import OVERHEAD, decode_frame, encode_frame, iter_frames
from stanchion.spool.priority import PriorityTable
from stanchion.spool.reader import read_segment
from stanchion.spool.segment import SegmentInfo, list_segments, parse_segment_name, segment_name
from stanchion.spool.store import Spool
from stanchion.timestamps import NanoTime

QUIET = settings(max_examples=120, deadline=None)


@contextmanager
def fresh_spool(**over: Any) -> Iterator[Spool]:
    """A spool in a directory of its own.

    Not the ``tmp_path`` fixture. Hypothesis runs a hundred examples inside one
    test function, the fixture is created once for all of them, and a spool
    that found the previous example's segments still on disk read them back as
    its own. The first version of these tests did exactly that and the failure
    looked like the spool inventing records.
    """
    with tempfile.TemporaryDirectory() as raw:
        settings_for: dict[str, Any] = {
            "directory": Path(raw) / "spool",
            "budget": Budget(limit_bytes=4_000_000),
            "segment_bytes": 4096,
            "compress": False,
        }
        settings_for.update(over)
        yield Spool(**settings_for)


messages = st.text(min_size=0, max_size=400)
units = st.sampled_from(["pump-bridge", "price-sign", "payment-kernel", "tank-gauge"])
instants = st.integers(min_value=0, max_value=2_000_000_000).map(
    lambda seconds: NanoTime(seconds * 1_000_000_000)
)
labels = st.dictionaries(
    st.text(alphabet="abcdefghijklmnopqrstuvwxyz_", min_size=1, max_size=8),
    st.text(min_size=0, max_size=16),
    max_size=4,
)


def records() -> st.SearchStrategy[LogRecord]:
    return st.builds(
        LogRecord,
        site=st.just("US-TX-0417"),
        unit=units,
        stream=st.sampled_from(list(Stream)),
        observed_at=instants,
        message=messages,
        labels=labels,
    )


def payloads() -> st.SearchStrategy[dict[str, Any]]:
    return records().map(lambda record: record.as_dict())


@given(payloads(), st.booleans())
@settings(max_examples=200, deadline=None)
def test_a_frame_round_trips(payload: dict[str, Any], compress: bool) -> None:
    frame, _ = decode_frame(encode_frame(payload, compress=compress))
    assert frame.payload == payload


@given(payloads())
@settings(max_examples=200, deadline=None)
def test_compression_does_not_change_what_comes_back(payload: dict[str, Any]) -> None:
    plain, _ = decode_frame(encode_frame(payload, compress=False))
    squeezed, _ = decode_frame(encode_frame(payload, compress=True))
    assert plain.payload == squeezed.payload


@given(payloads(), st.booleans())
@settings(max_examples=200, deadline=None)
def test_a_frame_reports_the_size_it_occupies(payload: dict[str, Any], compress: bool) -> None:
    raw = encode_frame(payload, compress=compress)
    frame, offset = decode_frame(raw)
    assert frame.stored_size == len(raw)
    assert offset == len(raw)


@given(st.lists(payloads(), min_size=1, max_size=12))
@settings(max_examples=120, deadline=None)
def test_every_frame_in_a_run_comes_back_in_order(items: list[dict[str, Any]]) -> None:
    data = b"".join(encode_frame(item) for item in items)
    frames, consumed, note = iter_frames(data)
    assert note is None
    assert consumed == len(data)
    assert [frame.payload for frame in frames] == items


@given(st.lists(payloads(), min_size=2, max_size=8), st.integers(min_value=1, max_value=64))
@settings(max_examples=150, deadline=None)
def test_a_torn_tail_loses_only_the_torn_frame(items: list[dict[str, Any]], bite: int) -> None:
    encoded = [encode_frame(item) for item in items]
    data = b"".join(encoded)
    assume(bite < len(encoded[-1]))
    frames, consumed, _ = iter_frames(data[: len(data) - bite])
    assert [frame.payload for frame in frames] == items[:-1]
    assert consumed == len(data) - len(encoded[-1])


@given(payloads())
@settings(max_examples=150, deadline=None)
def test_a_frame_with_a_flipped_byte_does_not_decode_quietly(
    payload: dict[str, Any],
) -> None:
    raw = bytearray(encode_frame(payload, compress=False))
    assume(len(raw) > OVERHEAD + 1)
    position = OVERHEAD
    raw[position] ^= 0xFF
    try:
        frame, _ = decode_frame(bytes(raw))
    except SpoolError:
        return
    assert frame.payload != payload


@given(st.integers(min_value=0, max_value=999_999_999_999), st.sampled_from(["payment", "chatter"]))
@settings(max_examples=200, deadline=None)
def test_a_segment_name_round_trips(sequence: int, klass: str) -> None:
    assert parse_segment_name(segment_name(sequence, klass)) == (sequence, klass)


@given(st.integers(min_value=0, max_value=999_999_999_999))
@settings(max_examples=200, deadline=None)
def test_segment_names_sort_the_way_the_numbers_do(sequence: int) -> None:
    later = min(sequence + 1, 999_999_999_999)
    assert segment_name(sequence, "payment") <= segment_name(later, "payment")


@given(st.lists(records(), min_size=1, max_size=25))
@QUIET
def test_a_spool_keeps_everything_it_accepted(batch: list[LogRecord]) -> None:
    with fresh_spool() as spool:
        for record in batch:
            spool.append(record)
        spool.seal_all()
        found: list[dict[str, Any]] = []
        for info in list_segments(spool.directory):
            found.extend(read_segment(info.path).payloads)
    assert [item["message"] for item in found] == [record.message for record in batch]


@given(st.lists(records(), min_size=1, max_size=25))
@QUIET
def test_a_spool_never_reports_more_than_it_holds(batch: list[LogRecord]) -> None:
    with fresh_spool() as spool:
        for record in batch:
            spool.append(record)
        spool.seal_all()
        used = spool.usage().used
        on_disk = sum(info.size for info in list_segments(spool.directory))
    assert used == on_disk


@given(st.lists(records(), min_size=1, max_size=40))
@QUIET
def test_a_spool_stays_inside_its_budget(batch: list[LogRecord]) -> None:
    budget = Budget(limit_bytes=64 * 1024)
    with fresh_spool(budget=budget, segment_bytes=8192) as spool:
        for record in batch:
            try:
                spool.append(record)
            except SpoolError:
                # A refusal is a legitimate outcome. What is not legitimate is
                # accepting a write that puts the spool over its limit.
                break
        spool.seal_all()
        used = spool.usage().used
    assert used <= budget.limit_bytes


@given(st.lists(records(), min_size=1, max_size=20))
@QUIET
def test_a_record_lands_in_its_own_class(batch: list[LogRecord]) -> None:
    table = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})
    with fresh_spool(table=table) as spool:
        for record in batch:
            receipt = spool.append(record)
            assert receipt.klass == table.class_for(record.unit).name
        spool.seal_all()


@given(st.lists(records(), min_size=1, max_size=20))
@QUIET
def test_a_segment_only_ever_holds_one_class(batch: list[LogRecord]) -> None:
    # This is what makes eviction safe: a segment is deleted whole, so one that
    # mixed payment records with chatter could not be deleted at all.
    table = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})
    with fresh_spool(table=table, segment_bytes=1024) as spool:
        for record in batch:
            spool.append(record)
        spool.seal_all()
        for info in list_segments(spool.directory):
            classes = {
                table.class_for(str(item["unit"])).name for item in read_segment(info.path).payloads
            }
            assert classes <= {info.klass}


@given(st.lists(records(), min_size=1, max_size=20))
@QUIET
def test_reopening_a_spool_finds_what_the_last_one_left(batch: list[LogRecord]) -> None:
    with fresh_spool() as spool:
        for record in batch:
            spool.append(record)
        spool.detach()
        again = Spool(
            directory=spool.directory,
            budget=Budget(limit_bytes=4_000_000),
            segment_bytes=4096,
            compress=False,
        )
        again.open()
        found: list[dict[str, Any]] = []
        for info in list_segments(again.directory):
            found.extend(read_segment(info.path).payloads)
    assert [item["message"] for item in found] == [record.message for record in batch]


@given(st.lists(st.integers(min_value=1, max_value=4096), min_size=0, max_size=20))
@settings(max_examples=150, deadline=None)
def test_usage_is_the_sum_of_what_it_was_given(sizes: list[int]) -> None:
    segments = [
        SegmentInfo(
            path=Path(f"{index:012d}-standard.spool"), sequence=index, klass="standard", size=size
        )
        for index, size in enumerate(sizes)
    ]
    usage = measure(segments, open_bytes=0)
    assert usage.used == sum(sizes)
    assert usage.segments == len(sizes)


@pytest.mark.parametrize("limit", [1024, 65536, 1_048_576])
def test_a_budget_admits_nothing_larger_than_itself(limit: int) -> None:
    budget = Budget(limit_bytes=limit)
    assert not budget.admits(measure([], open_bytes=0), limit + 1)
