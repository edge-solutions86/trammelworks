from __future__ import annotations

import zlib
from typing import Any

import pytest
from hypothesis import given
from hypothesis import strategies as st

from stanchion.errors import SpoolError
from stanchion.spool.frame import (
    FLAG_DEFLATE,
    HEADER_SIZE,
    MAGIC,
    OVERHEAD,
    decode_frame,
    encode_frame,
    frame_length,
    iter_frames,
)

SMALL: dict[str, Any] = {"site": "US-TX-0417", "message": "nozzle 1 lifted"}
BIG: dict[str, Any] = {"site": "US-TX-0417", "message": "auth approved " * 60}


def test_a_frame_starts_with_the_magic() -> None:
    assert encode_frame(SMALL).startswith(MAGIC)


def test_a_frame_round_trips() -> None:
    frame, offset = decode_frame(encode_frame(SMALL))
    assert frame.payload == SMALL
    assert offset == len(encode_frame(SMALL))


def test_a_small_payload_is_left_uncompressed() -> None:
    encoded = encode_frame(SMALL)
    assert encoded[3] & FLAG_DEFLATE == 0
    assert decode_frame(encoded)[0].compressed is False


def test_a_repetitive_payload_is_compressed() -> None:
    encoded = encode_frame(BIG)
    assert encoded[3] & FLAG_DEFLATE == FLAG_DEFLATE
    assert decode_frame(encoded)[0].compressed is True


def test_compression_actually_makes_it_smaller() -> None:
    assert len(encode_frame(BIG)) < len(encode_frame(BIG, compress=False))


def test_compression_can_be_turned_off() -> None:
    assert encode_frame(BIG, compress=False)[3] & FLAG_DEFLATE == 0


def test_a_compressed_frame_round_trips() -> None:
    assert decode_frame(encode_frame(BIG))[0].payload == BIG


def test_the_stored_size_is_the_whole_frame() -> None:
    encoded = encode_frame(SMALL)
    assert decode_frame(encoded)[0].stored_size == len(encoded)


def test_the_overhead_is_twelve_bytes() -> None:
    assert OVERHEAD == 12
    encoded = encode_frame(SMALL, compress=False)
    body = len(encoded) - OVERHEAD
    assert int.from_bytes(encoded[4:8], "little") == body


def test_a_payload_that_cannot_be_encoded_is_refused() -> None:
    with pytest.raises(SpoolError, match="cannot be encoded"):
        encode_frame({"bad": object()})


def test_frame_length_reads_the_header() -> None:
    encoded = encode_frame(SMALL)
    assert frame_length(encoded) == len(encoded)


def test_frame_length_of_a_short_buffer_is_none() -> None:
    assert frame_length(encode_frame(SMALL)[:4]) is None


def test_frame_length_of_a_truncated_body_is_none() -> None:
    assert frame_length(encode_frame(SMALL)[:-2]) is None


def test_frame_length_refuses_a_wrong_magic() -> None:
    with pytest.raises(SpoolError, match="magic"):
        frame_length(b"XX" + encode_frame(SMALL)[2:])


def test_frame_length_refuses_an_impossible_length() -> None:
    encoded = bytearray(encode_frame(SMALL))
    encoded[4:8] = (2**30).to_bytes(4, "little")
    with pytest.raises(SpoolError, match="impossible length"):
        frame_length(bytes(encoded))


def test_a_truncated_frame_is_refused() -> None:
    with pytest.raises(SpoolError, match="truncated"):
        decode_frame(encode_frame(SMALL)[:-1])


def test_a_flipped_payload_byte_is_caught() -> None:
    encoded = bytearray(encode_frame(SMALL, compress=False))
    encoded[HEADER_SIZE] ^= 0xFF
    with pytest.raises(SpoolError, match="checksum"):
        decode_frame(bytes(encoded))


def test_a_flipped_checksum_byte_is_caught() -> None:
    encoded = bytearray(encode_frame(SMALL))
    encoded[-1] ^= 0xFF
    with pytest.raises(SpoolError, match="checksum"):
        decode_frame(bytes(encoded))


def test_a_frame_from_another_format_version_is_refused() -> None:
    encoded = bytearray(encode_frame(SMALL))
    encoded[2] = 99
    with pytest.raises(SpoolError, match="another spool format"):
        decode_frame(bytes(encoded))


def test_a_payload_that_is_not_json_is_refused() -> None:
    body = b"not json at all"
    header = MAGIC + bytes([1, 0]) + len(body).to_bytes(4, "little")
    encoded = header + body + zlib.crc32(body).to_bytes(4, "little")
    with pytest.raises(SpoolError, match="not json"):
        decode_frame(encoded)


def test_a_payload_that_is_not_an_object_is_refused() -> None:
    body = b"[1,2,3]"
    header = MAGIC + bytes([1, 0]) + len(body).to_bytes(4, "little")
    encoded = header + body + zlib.crc32(body).to_bytes(4, "little")
    with pytest.raises(SpoolError, match="not an object"):
        decode_frame(encoded)


def test_a_frame_flagged_deflate_that_is_not_is_refused() -> None:
    encoded = bytearray(encode_frame(SMALL, compress=False))
    encoded[3] |= FLAG_DEFLATE
    with pytest.raises(SpoolError, match="will not decompress"):
        decode_frame(bytes(encoded))


def test_two_frames_decode_in_order() -> None:
    data = encode_frame({"n": 1}) + encode_frame({"n": 2})
    frames, consumed, note = iter_frames(data)
    assert [frame.payload["n"] for frame in frames] == [1, 2]
    assert consumed == len(data)
    assert note is None


def test_an_empty_buffer_gives_nothing() -> None:
    assert iter_frames(b"") == ([], 0, None)


def test_a_torn_tail_stops_the_walk_without_losing_the_head() -> None:
    good = encode_frame({"n": 1})
    data = good + encode_frame({"n": 2})[:6]
    frames, consumed, note = iter_frames(data)
    assert [frame.payload["n"] for frame in frames] == [1]
    assert consumed == len(good)
    assert note is not None


def test_a_corrupt_middle_frame_stops_the_walk_there() -> None:
    first = encode_frame({"n": 1}, compress=False)
    second = bytearray(encode_frame({"n": 2}, compress=False))
    second[HEADER_SIZE] ^= 0xFF
    frames, consumed, note = iter_frames(first + bytes(second))
    assert len(frames) == 1
    assert consumed == len(first)
    assert note is not None and "checksum" in note


def test_the_consumed_offset_is_where_a_truncate_should_land() -> None:
    good = encode_frame({"n": 1})
    data = good + b"\x00\x00\x00"
    _, consumed, _ = iter_frames(data)
    assert data[:consumed] == good


@given(st.dictionaries(st.text(min_size=1, max_size=8), st.text(max_size=40), max_size=6))
def test_any_flat_mapping_round_trips(payload: dict[str, str]) -> None:
    assert decode_frame(encode_frame(payload))[0].payload == payload
