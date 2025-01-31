"""Nanosecond timestamps.

The CRI runtime writes RFC3339 with nine fractional digits. ``datetime`` holds
six. Truncating on the way in is not free: the payment kernel emits an
authorisation request and its response inside the same microsecond often enough
that dropping the last three digits reorders them, and a reordered auth pair is
exactly the evidence a chargeback review asks for.

So the internal representation is an integer count of nanoseconds since the
epoch. ``datetime`` appears only where something has to be rendered or compared
against a calendar, and the conversion is explicit at every one of those points.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta, timezone

from stanchion.errors import ParseError

NS_PER_US = 1_000
NS_PER_MS = 1_000_000
NS_PER_SECOND = 1_000_000_000

_RFC3339 = re.compile(
    r"^(\d{4})-(\d{2})-(\d{2})"
    r"[Tt ](\d{2}):(\d{2}):(\d{2})"
    r"(?:\.(\d{1,9}))?"
    r"(Z|z|[+-]\d{2}:?\d{2})$"
)


def _offset(raw: str) -> timezone:
    if raw in ("Z", "z"):
        return UTC
    sign = -1 if raw[0] == "-" else 1
    body = raw[1:].replace(":", "")
    hours = int(body[:2])
    minutes = int(body[2:])
    return timezone(sign * timedelta(hours=hours, minutes=minutes))


@dataclass(frozen=True, order=True, slots=True)
class NanoTime:
    """An instant, to the nanosecond, counted from the Unix epoch."""

    epoch_ns: int

    @classmethod
    def parse(cls, raw: str) -> NanoTime:
        """Parse an RFC3339 timestamp with zero to nine fractional digits."""
        match = _RFC3339.match(raw.strip())
        if match is None:
            raise ParseError("malformed rfc3339 timestamp", detail=raw)
        year, month, day, hour, minute, second, fraction, zone = match.groups()
        try:
            base = datetime(
                int(year),
                int(month),
                int(day),
                int(hour),
                int(minute),
                int(second),
                tzinfo=_offset(zone),
            )
        except ValueError as exc:
            raise ParseError("impossible rfc3339 timestamp", detail=raw) from exc
        nanos = int((fraction or "").ljust(9, "0")) if fraction else 0
        whole = int(base.timestamp()) * NS_PER_SECOND
        return cls(whole + nanos)

    @classmethod
    def from_datetime(cls, value: datetime) -> NanoTime:
        if value.tzinfo is None:
            raise ParseError("naive datetime has no instant", detail=repr(value))
        seconds = int(value.replace(microsecond=0).timestamp())
        return cls(seconds * NS_PER_SECOND + value.microsecond * NS_PER_US)

    @classmethod
    def from_micros(cls, micros: int) -> NanoTime:
        """journald counts microseconds since the epoch, so accept that too."""
        return cls(micros * NS_PER_US)

    @property
    def seconds(self) -> int:
        return self.epoch_ns // NS_PER_SECOND

    @property
    def nanos_in_second(self) -> int:
        return self.epoch_ns % NS_PER_SECOND

    def to_datetime(self) -> datetime:
        """Convert to an aware UTC datetime, truncating below the microsecond."""
        whole = datetime.fromtimestamp(self.seconds, tz=UTC)
        return whole.replace(microsecond=self.nanos_in_second // NS_PER_US)

    def isoformat(self) -> str:
        """Render with all nine fractional digits, always in UTC."""
        stem = datetime.fromtimestamp(self.seconds, tz=UTC).strftime("%Y-%m-%dT%H:%M:%S")
        return f"{stem}.{self.nanos_in_second:09d}Z"

    def shifted(self, delta_ns: int) -> NanoTime:
        return NanoTime(self.epoch_ns + delta_ns)

    def __sub__(self, other: NanoTime) -> int:
        """Difference in nanoseconds, which is what drift arithmetic wants."""
        return self.epoch_ns - other.epoch_ns

    def __str__(self) -> str:
        return self.isoformat()
