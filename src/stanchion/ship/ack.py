"""How much of each segment the collector has admitted to holding.

A watermark is one number per segment: the count of records the collector has
acknowledged, from the start. It is deliberately a count and not a set of ids.
A set would let a gap in the middle be recorded, and there is nothing useful to
do with that knowledge: the batches are cut in order, so the only thing that can
be resumed from is a prefix.

The watermark only ever moves forward and only on an acknowledgement. Sending is
not acknowledging. A batch that went out and was never answered has been sent as
far as the modem is concerned and not at all as far as anyone should behave, and
the whole point of at least once delivery is that we send it again.

A segment whose watermark reaches its record count is **done**, and only then may
eviction have it. Everything else is pinned. That is the rule that makes the
spool budget and the uplink interact honestly: a site that cannot reach the
collector fills up and evicts by class, rather than quietly deleting records that
were never delivered.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from stanchion.errors import ShipError
from stanchion.jsonstore import JsonStore

FORMAT_VERSION = 1
FILENAME = "acks.json"


@dataclass(slots=True)
class Watermark:
    """Progress through one segment."""

    segment: str
    total: int
    acked: int = 0
    attempts: int = 0
    failed: int = 0

    def __post_init__(self) -> None:
        if self.total < 0:
            raise ShipError("a segment cannot hold a negative number of records")
        if not 0 <= self.acked <= self.total:
            raise ShipError(
                "watermark is outside the segment", detail=f"{self.acked} of {self.total}"
            )

    @property
    def done(self) -> bool:
        return self.acked >= self.total

    @property
    def outstanding(self) -> int:
        return self.total - self.acked

    def advance(self, count: int) -> int:
        """Move the watermark on by an acknowledged count."""
        if count < 0:
            raise ShipError("cannot acknowledge a negative count", detail=str(count))
        if self.acked + count > self.total:
            raise ShipError(
                "acknowledgement runs past the end of the segment",
                detail=f"{self.acked}+{count} > {self.total}",
            )
        self.acked += count
        return self.acked

    def resize(self, total: int) -> None:
        """Shrink the segment under this watermark and start it again.

        Compaction is the only caller. Everything acknowledged has left the
        file, so the count of what has been acknowledged of what is left is
        zero, and it is zero rather than carried across because the records the
        old number referred to are not there any more.
        """
        if total < 0:
            raise ShipError("a segment cannot hold a negative number of records")
        if total > self.total:
            raise ShipError("a sealed segment cannot grow", detail=f"{self.total} then {total}")
        self.total = total
        self.acked = 0

    def as_dict(self) -> dict[str, Any]:
        return {
            "segment": self.segment,
            "total": self.total,
            "acked": self.acked,
            "attempts": self.attempts,
            "failed": self.failed,
        }

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> Watermark:
        try:
            return cls(
                segment=str(payload["segment"]),
                total=int(payload["total"]),
                acked=int(payload.get("acked", 0)),
                attempts=int(payload.get("attempts", 0)),
                failed=int(payload.get("failed", 0)),
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise ShipError("watermark is missing a field", detail=str(exc)) from exc


@dataclass(slots=True)
class AckLedger:
    """Every segment the shipper is part way through."""

    marks: dict[str, Watermark] = field(default_factory=dict)

    def mark(self, segment: str, total: int) -> Watermark:
        """Get the watermark for a segment, starting one if it is new."""
        found = self.marks.get(segment)
        if found is None:
            found = Watermark(segment=segment, total=total)
            self.marks[segment] = found
        elif total < found.total:
            # A compaction that landed and did not get as far as writing the
            # ledger looks exactly like this. Take the file's word for it and
            # send what is there again, which is what at least once delivery
            # already promises.
            found.resize(total)
        elif total > found.total:
            raise ShipError(
                "a segment grew under the watermark",
                detail=f"{segment}: {found.total} then {total}",
            )
        return found

    def resize(self, segment: str, total: int) -> Watermark:
        """Tell the ledger a segment has been rewritten shorter."""
        found = self.marks.get(segment)
        if found is None:
            raise ShipError("resized a segment nobody is shipping", detail=segment)
        found.resize(total)
        return found

    def acknowledge(self, segment: str, count: int) -> Watermark:
        found = self.marks.get(segment)
        if found is None:
            raise ShipError("acknowledged a segment nobody is shipping", detail=segment)
        found.advance(count)
        return found

    def done_segments(self) -> tuple[str, ...]:
        return tuple(sorted(name for name, mark in self.marks.items() if mark.done))

    def pending_segments(self) -> tuple[str, ...]:
        return tuple(sorted(name for name, mark in self.marks.items() if not mark.done))

    @property
    def outstanding(self) -> int:
        return sum(mark.outstanding for mark in self.marks.values())

    def forget(self, segment: str) -> bool:
        return self.marks.pop(segment, None) is not None

    def sweep(self, live: set[str]) -> int:
        """Drop watermarks for segments that are no longer on disk."""
        gone = [name for name in self.marks if name not in live]
        for name in gone:
            del self.marks[name]
        return len(gone)


@dataclass(slots=True)
class AckStore:
    """The ledger on disk, written whole and atomically."""

    directory: Path
    store: JsonStore = field(init=False)

    def __post_init__(self) -> None:
        self.directory = Path(self.directory)
        self.store = JsonStore(path=self.directory / FILENAME, version=FORMAT_VERSION, indent=1)

    @property
    def path(self) -> Path:
        return self.store.path

    @property
    def corrupt(self) -> int:
        return self.store.corrupt

    def load(self) -> AckLedger:
        payload = self.store.load()
        if payload is None:
            return AckLedger()
        ledger = AckLedger()
        try:
            for raw in payload.get("marks", []):
                mark = Watermark.from_dict(raw)
                ledger.marks[mark.segment] = mark
        except ShipError:
            self.store.corrupt += 1
            return AckLedger()
        return ledger

    def save(self, ledger: AckLedger) -> Path:
        return self.store.save(
            {"marks": [ledger.marks[name].as_dict() for name in sorted(ledger.marks)]}
        )
