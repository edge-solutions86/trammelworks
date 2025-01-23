"""The two record shapes everything downstream is written against.

A ``Fragment`` is one physical line as it sat in a container runtime log file. A
``LogRecord`` is one line as the application meant to write it. They are not the
same thing and conflating them is the single most expensive mistake available
here: the CRI runtime splits an application line at 16 KiB and marks every piece
but the last as partial, so a stack trace from the payment kernel arrives as
four fragments that must become one record.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any


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

    observed_at: datetime
    stream: Stream
    message: str
    source: SourceRef
    partial: bool = False

    def __post_init__(self) -> None:
        if self.observed_at.tzinfo is None:
            raise ValueError("fragment timestamps must carry a timezone")

    @property
    def utc(self) -> datetime:
        return self.observed_at.astimezone(UTC)

    def with_message(self, message: str) -> Fragment:
        return replace(self, message=message)


@dataclass(frozen=True, slots=True)
class LogRecord:
    """One reassembled application line, ready for the rest of the pipeline."""

    site: str
    unit: str
    stream: Stream
    observed_at: datetime
    message: str
    sources: tuple[SourceRef, ...] = ()
    labels: dict[str, str] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.site:
            raise ValueError("site must not be empty")
        if not self.unit:
            raise ValueError("unit must not be empty")
        if self.observed_at.tzinfo is None:
            raise ValueError("record timestamps must carry a timezone")

    @property
    def utc(self) -> datetime:
        return self.observed_at.astimezone(UTC)

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

    def as_dict(self) -> dict[str, Any]:
        return {
            "site": self.site,
            "unit": self.unit,
            "stream": self.stream.value,
            "observed_at": self.utc.isoformat().replace("+00:00", "Z"),
            "message": self.message,
            "labels": dict(self.labels),
            "sources": [str(ref) for ref in self.sources],
        }
