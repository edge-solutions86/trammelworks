"""The two record shapes everything downstream is written against.

A ``Fragment`` is one physical line as it sat in a container runtime log file. A
``LogRecord`` is one line as the application meant to write it. They are not the
same thing and conflating them is the single most expensive mistake available
here: the CRI runtime splits an application line at 16 KiB and marks every piece
but the last as partial, so a stack trace from the payment kernel arrives as
four fragments that must become one record.

A record carries two times once it has been through clock correction:
``observed_at`` is what the box claimed and never changes, and ``corrected_at``
is what we believe. Keeping both is not tidiness. A dispute review compares our
record against the till roll, and "the box said 11:29:53 and we think it was
really 11:30:00" is an answer, while a single silently adjusted timestamp is not.

Both carry a ``NanoTime`` rather than a ``datetime``. The runtime writes nine
fractional digits and ``datetime`` holds six, so the record types used to lose
three digits at the door and the ordering that goes with them.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import datetime
from enum import StrEnum
from typing import Any

from stanchion.timestamps import NanoTime


class Stream(StrEnum):
    """Which of the two standard descriptors the container wrote to."""

    STDOUT = "stdout"
    STDERR = "stderr"

    @classmethod
    def parse(cls, raw: str) -> Stream:
        cleaned = raw.strip().lower()
        for member in cls:
            if member.value == cleaned:
                return member
        raise ValueError(f"unknown stream {raw!r}")


@dataclass(frozen=True, slots=True)
class SourceRef:
    """Where a fragment physically came from.

    Kept on every fragment so that a support engineer holding a shipped record
    can go back to the site image and point at the bytes. ``length`` is the byte
    length of the line as stored, terminator included.
    """

    path: str
    offset: int
    length: int

    def __post_init__(self) -> None:
        if self.offset < 0:
            raise ValueError(f"negative offset {self.offset}")
        if self.length < 0:
            raise ValueError(f"negative length {self.length}")

    @property
    def end(self) -> int:
        return self.offset + self.length

    def shifted(self, delta: int) -> SourceRef:
        return replace(self, offset=self.offset + delta)

    def __str__(self) -> str:
        return f"{self.path}@{self.offset}+{self.length}"


@dataclass(frozen=True, slots=True)
class Fragment:
    """One physical line read out of a container runtime log file."""

    observed_at: NanoTime
    stream: Stream
    message: str
    source: SourceRef
    partial: bool = False
    lossy: bool = False
    labels: dict[str, str] = field(default_factory=dict)

    @property
    def complete(self) -> bool:
        return not self.partial

    def to_datetime(self) -> datetime:
        return self.observed_at.to_datetime()

    def with_message(self, message: str) -> Fragment:
        return replace(self, message=message)


@dataclass(frozen=True, slots=True)
class LogRecord:
    """One reassembled application line, ready for the rest of the pipeline."""

    site: str
    unit: str
    stream: Stream
    observed_at: NanoTime
    message: str
    sources: tuple[SourceRef, ...] = ()
    labels: dict[str, str] = field(default_factory=dict)
    lossy: bool = False
    corrected_at: NanoTime | None = None
    clock_confidence: str = "unknown"

    def __post_init__(self) -> None:
        if not self.site:
            raise ValueError("site must not be empty")
        if not self.unit:
            raise ValueError("unit must not be empty")

    def to_datetime(self) -> datetime:
        """For anything that has to talk to a calendar. Loses three digits."""
        return self.observed_at.to_datetime()

    @property
    def byte_length(self) -> int:
        """Length of the message in UTF-8, which is what the meter counts."""
        return len(self.message.encode("utf-8"))

    @property
    def fragment_count(self) -> int:
        return len(self.sources)

    def with_labels(self, **extra: str) -> LogRecord:
        merged = dict(self.labels)
        merged.update(extra)
        return replace(self, labels=merged)

    @property
    def best_at(self) -> NanoTime:
        """The corrected time where there is one, otherwise the raw reading."""
        return self.corrected_at if self.corrected_at is not None else self.observed_at

    def as_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "site": self.site,
            "unit": self.unit,
            "stream": self.stream.value,
            "observed_at": self.observed_at.isoformat(),
            "message": self.message,
            "labels": dict(self.labels),
            "sources": [str(ref) for ref in self.sources],
            "clock_confidence": self.clock_confidence,
        }
        if self.corrected_at is not None:
            payload["corrected_at"] = self.corrected_at.isoformat()
        return payload
