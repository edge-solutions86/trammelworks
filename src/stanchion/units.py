"""Byte sizes and durations as they are written in a site configuration file.

Commissioning engineers write ``spool_budget: 512MiB`` and ``flush_every: 90s``
into a YAML file on a laptop in a car park. Both spellings have to survive that,
and both have to round trip so that a value read back out of a status report is
the same string that went in.

Binary and decimal suffixes are both accepted and they mean different things.
The LTE plan is sold in decimal megabytes and the disk is formatted in binary
mebibytes, so a parser that quietly treats them as equal is off by 4.8% on the
one number that costs money.
"""

from __future__ import annotations

import re
from datetime import timedelta

from stanchion.errors import ConfigError

BYTE_SUFFIXES: dict[str, int] = {
    "b": 1,
    "kb": 1000,
    "mb": 1000**2,
    "gb": 1000**3,
    "tb": 1000**4,
    "kib": 1024,
    "mib": 1024**2,
    "gib": 1024**3,
    "tib": 1024**4,
}

DURATION_SUFFIXES: dict[str, float] = {
    "ms": 0.001,
    "s": 1.0,
    "m": 60.0,
    "h": 3600.0,
    "d": 86400.0,
    "w": 604800.0,
}

_BYTES = re.compile(r"^(\d+(?:\.\d+)?)\s*([a-z]*)$")
_DURATION_PART = re.compile(r"(\d+(?:\.\d+)?)\s*(ms|[smhdw])")


def parse_bytes(raw: str | int) -> int:
    """Parse ``512MiB``, ``1.5 GB`` or a bare integer into a byte count."""
    if isinstance(raw, int):
        if raw < 0:
            raise ConfigError("negative byte size", detail=str(raw))
        return raw
    match = _BYTES.match(raw.strip().lower())
    if match is None:
        raise ConfigError("malformed byte size", detail=raw)
    number, suffix = match.groups()
    scale = BYTE_SUFFIXES.get(suffix or "b")
    if scale is None:
        raise ConfigError("unknown byte suffix", detail=raw)
    value = float(number) * scale
    if value != int(value):
        raise ConfigError("byte size is not a whole number of bytes", detail=raw)
    return int(value)


def format_bytes(count: int) -> str:
    """Render a byte count using the largest binary suffix that stays exact."""
    if count < 0:
        raise ConfigError("negative byte size", detail=str(count))
    for suffix, scale in (("TiB", 1024**4), ("GiB", 1024**3), ("MiB", 1024**2), ("KiB", 1024)):
        if count >= scale and count % scale == 0:
            return f"{count // scale}{suffix}"
    return f"{count}B"


def parse_duration(raw: str | int | float) -> timedelta:
    """Parse ``90s``, ``2h30m`` or a bare number of seconds."""
    if isinstance(raw, int | float):
        if raw < 0:
            raise ConfigError("negative duration", detail=str(raw))
        return timedelta(seconds=float(raw))
    text = raw.strip().lower().replace(" ", "")
    if not text:
        raise ConfigError("empty duration", detail=raw)
    if text.isdigit():
        return timedelta(seconds=int(text))
    total = 0.0
    consumed = 0
    for match in _DURATION_PART.finditer(text):
        if match.start() != consumed:
            raise ConfigError("malformed duration", detail=raw)
        total += float(match.group(1)) * DURATION_SUFFIXES[match.group(2)]
        consumed = match.end()
    if consumed != len(text):
        raise ConfigError("malformed duration", detail=raw)
    return timedelta(seconds=total)


def format_duration(value: timedelta) -> str:
    """Render a duration back into the compact spelling."""
    seconds = value.total_seconds()
    if seconds < 0:
        raise ConfigError("negative duration", detail=str(value))
    if seconds == 0:
        return "0s"
    parts: list[str] = []
    for suffix, scale in (("w", 604800), ("d", 86400), ("h", 3600), ("m", 60), ("s", 1)):
        whole = int(seconds // scale)
        if whole:
            parts.append(f"{whole}{suffix}")
            seconds -= whole * scale
    if seconds:
        parts.append(f"{int(round(seconds * 1000))}ms")
    return "".join(parts)
