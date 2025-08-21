"""How one record sits on disk.

A frame is::

    magic   2 bytes   b"SP"
    version 1 byte    the spool format
    flags   1 byte    bit 0 set when the payload is deflated
    length  4 bytes   little endian, payload length
    payload N bytes   the record as compact JSON
    crc     4 bytes   little endian crc32 of the payload

The checksum is over the payload only, not the header, and that is on purpose.
A torn write leaves a header describing a payload that is not all there, and the
length in that header is what tells the reader how far to look. Covering the
header as well would mean not being able to trust the length enough to find the
checksum.

Power on a forecourt goes away without warning, so a partial frame at the end of
a segment is normal rather than exceptional. It is detected and dropped, and
the bytes before it are still good, which is the entire reason for framing the
records at all rather than writing one JSON object per line.

Compression is per frame, not per segment. A segment compressed as a whole
cannot be appended to, and cannot be read past a corrupt point.
"""

from __future__ import annotations

import json
import zlib
from dataclasses import dataclass
from typing import Any

from stanchion.errors import SpoolError
from stanchion.version import SPOOL_FORMAT

MAGIC = b"SP"
HEADER_SIZE = 8
CRC_SIZE = 4
OVERHEAD = HEADER_SIZE + CRC_SIZE

FLAG_DEFLATE = 0x01

#: Below this a deflate stream is bigger than what went in, so it is not tried.
MIN_COMPRESS_BYTES = 200

MAX_PAYLOAD_BYTES = 16 * 1024 * 1024


@dataclass(frozen=True, slots=True)
class Frame:
    """One framed record, decoded."""

    payload: dict[str, Any]
    compressed: bool = False
    stored_size: int = 0


def encode_frame(payload: dict[str, Any], compress: bool = True) -> bytes:
    """Encode one record into its on disk form."""
    try:
        body = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise SpoolError("record cannot be encoded", detail=str(exc)) from exc
    flags = 0
    if compress and len(body) >= MIN_COMPRESS_BYTES:
        squeezed = zlib.compress(body, level=6)
        if len(squeezed) < len(body):
            body = squeezed
            flags |= FLAG_DEFLATE
    if len(body) > MAX_PAYLOAD_BYTES:
        raise SpoolError("record is too large to spool", detail=str(len(body)))
    header = MAGIC + bytes([SPOOL_FORMAT, flags]) + len(body).to_bytes(4, "little")
    return header + body + zlib.crc32(body).to_bytes(4, "little")


def frame_length(data: bytes, offset: int = 0) -> int | None:
    """Total length of the frame starting here, or None if it is not all there."""
    if offset + HEADER_SIZE > len(data):
        return None
    if data[offset : offset + 2] != MAGIC:
        raise SpoolError("frame does not start with the magic", detail=str(offset))
    size = int.from_bytes(data[offset + 4 : offset + 8], "little")
    if size > MAX_PAYLOAD_BYTES:
        raise SpoolError("frame claims an impossible length", detail=str(size))
    total = OVERHEAD + size
    if offset + total > len(data):
        return None
    return total


def decode_frame(data: bytes, offset: int = 0) -> tuple[Frame, int]:
    """Decode the frame at an offset, returning it and where the next one starts."""
    total = frame_length(data, offset)
    if total is None:
        raise SpoolError("frame is truncated", detail=str(offset))
    version = data[offset + 2]
    if version != SPOOL_FORMAT:
        raise SpoolError("frame was written by another spool format", detail=str(version))
    flags = data[offset + 3]
    size = total - OVERHEAD
    body_start = offset + HEADER_SIZE
    body = data[body_start : body_start + size]
    stored_crc = int.from_bytes(data[body_start + size : body_start + size + CRC_SIZE], "little")
    if zlib.crc32(body) != stored_crc:
        raise SpoolError("frame checksum does not match", detail=str(offset))
    compressed = bool(flags & FLAG_DEFLATE)
    if compressed:
        try:
            body = zlib.decompress(body)
        except zlib.error as exc:
            raise SpoolError("frame will not decompress", detail=str(offset)) from exc
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SpoolError("frame payload is not json", detail=str(offset)) from exc
    if not isinstance(payload, dict):
        raise SpoolError("frame payload is not an object", detail=str(offset))
    return Frame(payload=payload, compressed=compressed, stored_size=total), offset + total


def iter_frames(data: bytes) -> tuple[list[Frame], int, str | None]:
    """Decode every whole frame in a buffer.

    Returns the frames, how many bytes were consumed, and a note about why it
    stopped. A tail that is not a whole frame is not an error: it is what a
    power cut looks like, and the caller truncates the segment to the consumed
    length.
    """
    frames: list[Frame] = []
    offset = 0
    while offset < len(data):
        try:
            frame, offset = decode_frame(data, offset)
        except SpoolError as exc:
            return frames, offset, exc.message
        frames.append(frame)
    return frames, offset, None
