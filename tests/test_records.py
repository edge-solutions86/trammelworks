from __future__ import annotations

from datetime import UTC, datetime, timedelta, timezone

import pytest

from stanchion.records import Fragment, LogRecord, SourceRef, Stream

CENTRAL = timezone(timedelta(hours=-6))


def ts(second: int = 0) -> datetime:
    return datetime(2025, 3, 4, 11, 30, second, tzinfo=UTC)


def ref(offset: int = 0, length: int = 64) -> SourceRef:
    return SourceRef(path="/var/log/containers/payment.log", offset=offset, length=length)


def test_stream_parse_accepts_exact_values() -> None:
    assert Stream.parse("stdout") is Stream.STDOUT
    assert Stream.parse("stderr") is Stream.STDERR


def test_stream_parse_is_forgiving_about_case_and_padding() -> None:
    assert Stream.parse("  STDERR \n") is Stream.STDERR


def test_stream_parse_rejects_junk() -> None:
    with pytest.raises(ValueError, match="unknown stream"):
        Stream.parse("stdin")


def test_stream_is_a_string() -> None:
    assert Stream.STDOUT == "stdout"
    assert f"{Stream.STDERR}" == "stderr"


def test_source_ref_end_is_offset_plus_length() -> None:
    assert ref(offset=100, length=20).end == 120


def test_source_ref_rejects_negative_offset() -> None:
    with pytest.raises(ValueError, match="negative offset"):
        SourceRef(path="a", offset=-1, length=4)


def test_source_ref_rejects_negative_length() -> None:
    with pytest.raises(ValueError, match="negative length"):
        SourceRef(path="a", offset=0, length=-4)


def test_source_ref_allows_a_zero_length_line() -> None:
    assert SourceRef(path="a", offset=8, length=0).end == 8


def test_source_ref_shifted_moves_only_the_offset() -> None:
    moved = ref(offset=10, length=5).shifted(90)
    assert moved.offset == 100
    assert moved.length == 5
    assert moved.path == ref().path


def test_source_ref_str_is_greppable() -> None:
    assert str(ref(offset=7, length=3)) == "/var/log/containers/payment.log@7+3"


def test_fragment_requires_an_aware_timestamp() -> None:
    with pytest.raises(ValueError, match="must carry a timezone"):
        Fragment(
            observed_at=datetime(2025, 3, 4, 11, 30),
            stream=Stream.STDOUT,
            message="hi",
            source=ref(),
        )


def test_fragment_utc_normalizes_the_offset() -> None:
    frag = Fragment(
        observed_at=datetime(2025, 3, 4, 5, 30, tzinfo=CENTRAL),
        stream=Stream.STDOUT,
        message="hi",
        source=ref(),
    )
    assert frag.utc == ts()


def test_fragment_defaults_to_complete() -> None:
    frag = Fragment(observed_at=ts(), stream=Stream.STDOUT, message="hi", source=ref())
    assert frag.partial is False


def test_fragment_with_message_keeps_everything_else() -> None:
    frag = Fragment(
        observed_at=ts(), stream=Stream.STDERR, message="ab", source=ref(), partial=True
    )
    swapped = frag.with_message("abcd")
    assert swapped.message == "abcd"
    assert swapped.partial is True
    assert swapped.stream is Stream.STDERR
    assert swapped.source == frag.source


def test_fragment_is_hashable_so_it_can_go_in_a_set() -> None:
    frag = Fragment(observed_at=ts(), stream=Stream.STDOUT, message="hi", source=ref())
    assert len({frag, frag}) == 1


def record(**over: object) -> LogRecord:
    base: dict[str, object] = {
        "site": "US-TX-0417",
        "unit": "payment-kernel",
        "stream": Stream.STDOUT,
        "observed_at": ts(),
        "message": "auth approved",
    }
    base.update(over)
    return LogRecord(**base)  # type: ignore[arg-type]


def test_record_rejects_an_empty_site() -> None:
    with pytest.raises(ValueError, match="site must not be empty"):
        record(site="")


def test_record_rejects_an_empty_unit() -> None:
    with pytest.raises(ValueError, match="unit must not be empty"):
        record(unit="")


def test_record_rejects_a_naive_timestamp() -> None:
    with pytest.raises(ValueError, match="must carry a timezone"):
        record(observed_at=datetime(2025, 3, 4, 11, 30))


def test_record_byte_length_counts_utf8_not_characters() -> None:
    assert record(message="abc").byte_length == 3
    assert record(message="prix affýché").byte_length == 14


def test_record_fragment_count_follows_the_sources() -> None:
    assert record().fragment_count == 0
    assert record(sources=(ref(0, 10), ref(10, 10))).fragment_count == 2


def test_with_labels_merges_and_does_not_mutate() -> None:
    original = record(labels={"lane": "3"})
    tagged = original.with_labels(pump="7")
    assert tagged.labels == {"lane": "3", "pump": "7"}
    assert original.labels == {"lane": "3"}


def test_with_labels_overwrites_a_repeated_key() -> None:
    assert record(labels={"lane": "3"}).with_labels(lane="4").labels == {"lane": "4"}


def test_as_dict_renders_the_timestamp_with_a_z() -> None:
    payload = record().as_dict()
    assert payload["observed_at"] == "2025-03-04T11:30:00Z"
    assert payload["stream"] == "stdout"


def test_as_dict_converts_a_local_timestamp_to_utc() -> None:
    payload = record(observed_at=datetime(2025, 3, 4, 5, 30, tzinfo=CENTRAL)).as_dict()
    assert payload["observed_at"] == "2025-03-04T11:30:00Z"


def test_as_dict_stringifies_the_sources() -> None:
    payload = record(sources=(ref(0, 10),)).as_dict()
    assert payload["sources"] == ["/var/log/containers/payment.log@0+10"]


def test_as_dict_copies_the_labels() -> None:
    original = record(labels={"lane": "3"})
    payload = original.as_dict()
    assert isinstance(payload["labels"], dict)
    payload["labels"]["lane"] = "9"
    assert original.labels == {"lane": "3"}
