from __future__ import annotations

import pytest

from stanchion.ingest.join import Joiner, JoinLimits
from stanchion.records import Fragment, SourceRef, Stream
from stanchion.timestamps import NanoTime

SITE = "US-TX-0417"
UNIT = "payment-kernel"


def frag(
    message: str,
    *,
    partial: bool = False,
    stream: Stream = Stream.STDOUT,
    at: str = "2025-03-04T11:30:00.000000001Z",
    offset: int = 0,
    lossy: bool = False,
) -> Fragment:
    return Fragment(
        observed_at=NanoTime.parse(at),
        stream=stream,
        message=message,
        source=SourceRef(path="/var/log/pods/x/0.log", offset=offset, length=len(message) + 40),
        partial=partial,
        lossy=lossy,
    )


def joiner(**over: object) -> Joiner:
    return Joiner(site=SITE, unit=UNIT, **over)  # type: ignore[arg-type]


def test_a_complete_fragment_passes_straight_through() -> None:
    joined = joiner()
    record = joined.feed(frag("auth approved"))
    assert record is not None
    assert record.message == "auth approved"
    assert record.site == SITE
    assert record.unit == UNIT
    assert record.fragment_count == 1


def test_a_partial_fragment_produces_nothing_yet() -> None:
    assert joiner().feed(frag("half", partial=True)) is None


def test_two_chunks_become_one_record() -> None:
    joined = joiner()
    assert joined.feed(frag("hello ", partial=True)) is None
    record = joined.feed(frag("world"))
    assert record is not None
    assert record.message == "hello world"
    assert record.fragment_count == 2


def test_many_chunks_join_in_order() -> None:
    joined = joiner()
    for part in ["a", "b", "c", "d"]:
        joined.feed(frag(part, partial=True))
    record = joined.feed(frag("e"))
    assert record is not None
    assert record.message == "abcde"
    assert record.fragment_count == 5


def test_the_first_chunk_owns_the_timestamp() -> None:
    joined = joiner()
    joined.feed(frag("start", partial=True, at="2025-03-04T11:30:00.000000001Z"))
    record = joined.feed(frag("end", at="2025-03-04T11:31:59.999999999Z"))
    assert record is not None
    assert record.observed_at == NanoTime.parse("2025-03-04T11:30:00.000000001Z")


def test_the_streams_are_joined_independently() -> None:
    joined = joiner()
    joined.feed(frag("out ", partial=True, stream=Stream.STDOUT))
    joined.feed(frag("err ", partial=True, stream=Stream.STDERR))
    out = joined.feed(frag("done", stream=Stream.STDOUT))
    err = joined.feed(frag("boom", stream=Stream.STDERR))
    assert out is not None and out.message == "out done"
    assert err is not None and err.message == "err boom"


def test_a_complete_line_on_one_stream_does_not_disturb_the_other() -> None:
    joined = joiner()
    joined.feed(frag("pending ", partial=True, stream=Stream.STDERR))
    passthrough = joined.feed(frag("heartbeat", stream=Stream.STDOUT))
    assert passthrough is not None
    assert joined.pending_streams() == (Stream.STDERR,)


def test_the_record_keeps_every_source() -> None:
    joined = joiner()
    joined.feed(frag("a", partial=True, offset=0))
    record = joined.feed(frag("b", offset=100))
    assert record is not None
    assert [ref.offset for ref in record.sources] == [0, 100]


def test_the_stream_of_the_record_is_the_first_chunks() -> None:
    joined = joiner()
    joined.feed(frag("x", partial=True, stream=Stream.STDERR))
    record = joined.feed(frag("y", stream=Stream.STDERR))
    assert record is not None
    assert record.stream is Stream.STDERR


def test_an_empty_final_chunk_still_closes_the_line() -> None:
    joined = joiner()
    joined.feed(frag("body", partial=True))
    record = joined.feed(frag(""))
    assert record is not None
    assert record.message == "body"


def test_pending_streams_empties_once_the_line_closes() -> None:
    joined = joiner()
    joined.feed(frag("x", partial=True))
    joined.feed(frag("y"))
    assert joined.pending_streams() == ()


def test_a_line_over_the_cap_is_cut_and_labelled() -> None:
    joined = joiner(limits=JoinLimits(max_line_bytes=10))
    joined.feed(frag("0123456789", partial=True))
    record = joined.feed(frag("this is dropped"))
    assert record is not None
    assert record.message == "0123456789"
    assert record.labels["truncated"] == "true"


def test_the_cut_lands_on_the_byte_boundary() -> None:
    joined = joiner(limits=JoinLimits(max_line_bytes=6))
    joined.feed(frag("abcd", partial=True))
    record = joined.feed(frag("efgh"))
    assert record is not None
    assert record.message == "abcdef"


def test_the_cut_never_splits_a_multibyte_character() -> None:
    joined = joiner(limits=JoinLimits(max_line_bytes=5))
    joined.feed(frag("ab", partial=True))
    record = joined.feed(frag("é" * 4))
    assert record is not None
    assert record.message == "abé"
    assert record.byte_length <= 5


def test_chunks_after_the_cap_are_dropped_not_buffered() -> None:
    joined = joiner(limits=JoinLimits(max_line_bytes=4))
    joined.feed(frag("abcd", partial=True))
    joined.feed(frag("efgh", partial=True))
    joined.feed(frag("ijkl", partial=True))
    record = joined.feed(frag("mnop"))
    assert record is not None
    assert record.message == "abcd"
    assert joined.stats.dropped_chunks == 3


def test_a_capped_line_still_ends_when_the_final_chunk_arrives() -> None:
    joined = joiner(limits=JoinLimits(max_line_bytes=4))
    joined.feed(frag("abcdefgh", partial=True))
    joined.feed(frag("done"))
    assert joined.pending_streams() == ()


def test_the_chunk_count_cap_also_truncates() -> None:
    joined = joiner(limits=JoinLimits(max_line_bytes=10_000, max_chunks=3))
    for _ in range(3):
        joined.feed(frag("x", partial=True))
    record = joined.feed(frag("y"))
    assert record is not None
    assert record.message == "xxx"
    assert record.labels["truncated"] == "true"


def test_limits_reject_a_non_positive_cap() -> None:
    with pytest.raises(ValueError, match="max_line_bytes must be positive"):
        JoinLimits(max_line_bytes=0)
    with pytest.raises(ValueError, match="max_chunks must be positive"):
        JoinLimits(max_chunks=0)


def test_flush_emits_a_pending_line_as_truncated() -> None:
    joined = joiner()
    joined.feed(frag("unfinished", partial=True))
    records = joined.flush()
    assert len(records) == 1
    assert records[0].message == "unfinished"
    assert records[0].labels["truncated"] == "true"
    assert joined.pending_streams() == ()


def test_flush_with_nothing_pending_returns_nothing() -> None:
    assert joiner().flush() == []


def test_flush_can_target_one_stream() -> None:
    joined = joiner()
    joined.feed(frag("out", partial=True, stream=Stream.STDOUT))
    joined.feed(frag("err", partial=True, stream=Stream.STDERR))
    records = joined.flush(Stream.STDOUT)
    assert len(records) == 1
    assert joined.pending_streams() == (Stream.STDERR,)


def test_flush_of_an_absent_stream_is_a_no_op() -> None:
    joined = joiner()
    assert joined.flush(Stream.STDERR) == []


def test_a_lossy_chunk_marks_the_whole_record() -> None:
    joined = joiner()
    joined.feed(frag("good ", partial=True))
    record = joined.feed(frag("bad", lossy=True))
    assert record is not None
    assert record.lossy is True
    assert record.labels["lossy"] == "true"


def test_a_lossy_single_line_is_marked() -> None:
    record = joiner().feed(frag("bad", lossy=True))
    assert record is not None
    assert record.lossy is True


def test_a_clean_record_carries_no_labels() -> None:
    record = joiner().feed(frag("fine"))
    assert record is not None
    assert record.labels == {}


def test_the_stats_add_up() -> None:
    joined = joiner()
    joined.feed(frag("a", partial=True))
    joined.feed(frag("b"))
    joined.feed(frag("c"))
    assert joined.stats.fragments == 3
    assert joined.stats.records == 2
    assert joined.stats.joined == 1
    assert joined.stats.truncated == 0


def test_flush_is_counted_separately() -> None:
    joined = joiner()
    joined.feed(frag("open", partial=True))
    joined.flush()
    assert joined.stats.flushed_open == 1
    assert joined.stats.truncated == 1


def test_fragment_labels_reach_a_single_line_record() -> None:
    joined = joiner()
    tagged = Fragment(
        observed_at=NanoTime.parse("2025-03-04T11:30:00Z"),
        stream=Stream.STDOUT,
        message="x",
        source=SourceRef(path="a", offset=0, length=1),
        labels={"boot_id": "b" * 32},
    )
    record = joined.feed(tagged)
    assert record is not None
    assert record.labels["boot_id"] == "b" * 32


def test_the_first_chunk_owns_the_labels() -> None:
    joined = joiner()
    first = Fragment(
        observed_at=NanoTime.parse("2025-03-04T11:30:00Z"),
        stream=Stream.STDOUT,
        message="head ",
        source=SourceRef(path="a", offset=0, length=1),
        partial=True,
        labels={"monotonic_ns": "100"},
    )
    tail = Fragment(
        observed_at=NanoTime.parse("2025-03-04T11:30:01Z"),
        stream=Stream.STDOUT,
        message="tail",
        source=SourceRef(path="a", offset=1, length=1),
        labels={"monotonic_ns": "900"},
    )
    joined.feed(first)
    record = joined.feed(tail)
    assert record is not None
    assert record.labels["monotonic_ns"] == "100"
