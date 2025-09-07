"""How much of the disk the spool is allowed, and how much it is using.

The budget is a number the site is given, not a measurement of the filesystem.
A forecourt controller shares its disk with the payment kernel's own database and
with the runtime's image store, and filling the partition stops the tills. So the
spool is told what it may have and it stays inside that whatever the disk says is
free.

Two numbers come out of this and they are not the same:

* **used**, everything the spool is holding, sealed segments and the one being
  written
* **committed**, used plus the reserve

The reserve is room kept back so that an eviction always has somewhere to work.
Eviction has to seal the open segment before it can delete anything, and sealing
writes. A spool that filled its budget exactly would have no room for that write
and would deadlock at exactly the moment it most needed not to.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from stanchion.errors import SpoolError
from stanchion.spool.segment import SegmentInfo


@dataclass(frozen=True, slots=True)
class Usage:
    """What the spool is holding right now."""

    sealed_bytes: int = 0
    open_bytes: int = 0
    segments: int = 0
    by_class: dict[str, int] = field(default_factory=dict)

    @property
    def used(self) -> int:
        return self.sealed_bytes + self.open_bytes

    def of_class(self, klass: str) -> int:
        return self.by_class.get(klass, 0)


def measure(segments: list[SegmentInfo], open_bytes: int = 0) -> Usage:
    """Add up what is on disk."""
    by_class: dict[str, int] = {}
    total = 0
    for item in segments:
        by_class[item.klass] = by_class.get(item.klass, 0) + item.size
        total += item.size
    return Usage(
        sealed_bytes=total,
        open_bytes=open_bytes,
        segments=len(segments),
        by_class=by_class,
    )


@dataclass(frozen=True, slots=True)
class Budget:
    """What the spool may use, and the arithmetic on it."""

    limit_bytes: int
    reserve_ratio: float = 0.1

    def __post_init__(self) -> None:
        if self.limit_bytes <= 0:
            raise SpoolError("spool budget must be positive", detail=str(self.limit_bytes))
        if not 0.0 <= self.reserve_ratio < 1.0:
            raise SpoolError("reserve ratio must be in [0, 1)", detail=str(self.reserve_ratio))

    @property
    def reserve_bytes(self) -> int:
        return int(self.limit_bytes * self.reserve_ratio)

    @property
    def usable_bytes(self) -> int:
        """What may be filled before eviction has to run."""
        return self.limit_bytes - self.reserve_bytes

    def free(self, usage: Usage) -> int:
        """Bytes still available inside the usable part, never negative."""
        return max(0, self.usable_bytes - usage.used)

    def admits(self, usage: Usage, size: int) -> bool:
        """True when a write of this size fits without eviction."""
        if size < 0:
            raise SpoolError("cannot admit a negative size", detail=str(size))
        return usage.used + size <= self.usable_bytes

    def over_by(self, usage: Usage) -> int:
        """How much has to be freed to get back inside the usable part."""
        return max(0, usage.used - self.usable_bytes)

    def would_exceed_hard_limit(self, usage: Usage, size: int) -> bool:
        """True when even the reserve would not cover this write."""
        return usage.used + size > self.limit_bytes

    def fill_ratio(self, usage: Usage) -> float:
        """How full the spool is, against the whole budget rather than usable."""
        return usage.used / self.limit_bytes


@dataclass(slots=True)
class BudgetReport:
    """A line for the nightly summary."""

    used: int
    limit: int
    reserve: int
    segments: int
    by_class: dict[str, int] = field(default_factory=dict)

    @property
    def percent(self) -> float:
        return 100.0 * self.used / self.limit if self.limit else 0.0

    def __str__(self) -> str:
        return (
            f"spool {self.used}/{self.limit} bytes ({self.percent:.1f}%), {self.segments} segments"
        )


def report(budget: Budget, usage: Usage) -> BudgetReport:
    return BudgetReport(
        used=usage.used,
        limit=budget.limit_bytes,
        reserve=budget.reserve_bytes,
        segments=usage.segments,
        by_class=dict(usage.by_class),
    )
