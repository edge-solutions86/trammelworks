"""The monthly byte allowance, and who is allowed to spend the last of it.

The uplink is a metered LTE plan bought per site. Going over costs money per
megabyte and the estate finds out a month later, so the meter is not advisory.

The billing period is anchored to a day of the month rather than to the calendar
month, because that is how the plans are actually sold: a site commissioned on
the 14th bills from the 14th. Rolling on the first would let a site spend its
whole allowance twice inside one real billing period.

The part worth reading twice is the **floor**. Every priority class is given a
fraction of the allowance that nothing below it may touch. Chatter stops
shipping once the allowance is 70% gone; payment traffic keeps going to the last
byte. Without that, three days of price sign heartbeats fill the plan and the
one thing anyone needed off the site does not leave.

So the meter does not decide "is there room". It decides "is there room for
*this*", and the answer depends on which class is asking.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta

from stanchion.errors import ShipError
from stanchion.timestamps import NanoTime

DEFAULT_ALLOWANCE = 2 * 1000 * 1000 * 1000
DEFAULT_ANCHOR_DAY = 1

#: How much of the allowance each class may spend down to. A class with a floor
#: of 0.3 stops when 30% is left; one with 0.0 may spend the lot.
DEFAULT_FLOORS: dict[str, float] = {"payment": 0.0, "standard": 0.15, "chatter": 0.3}


def check_anchor(day: int) -> int:
    if not 1 <= day <= 28:
        # Above 28 the anchor is not a day every month has, and a plan that
        # bills on the 31st bills on the 28th in February anyway.
        raise ShipError("billing anchor day must be 1 to 28", detail=str(day))
    return day


def period_start(now: NanoTime, anchor_day: int) -> NanoTime:
    """The start of the billing period containing this instant."""
    check_anchor(anchor_day)
    stamp = now.to_datetime()
    if stamp.day >= anchor_day:
        start = stamp.replace(day=anchor_day, hour=0, minute=0, second=0, microsecond=0)
    else:
        first = stamp.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
        previous = first - timedelta(days=1)
        start = previous.replace(day=anchor_day, hour=0, minute=0, second=0, microsecond=0)
    return NanoTime.from_datetime(start)


def period_end(start: NanoTime, anchor_day: int) -> NanoTime:
    """One period on from a start, which is the next anchor day."""
    stamp = start.to_datetime()
    month = stamp.month + 1
    year = stamp.year + (month > 12)
    month = month - 12 if month > 12 else month
    return NanoTime.from_datetime(datetime(year, month, anchor_day, tzinfo=stamp.tzinfo))


@dataclass(slots=True)
class Meter:
    """What has been spent this billing period, and what may still be."""

    allowance_bytes: int = DEFAULT_ALLOWANCE
    anchor_day: int = DEFAULT_ANCHOR_DAY
    used_bytes: int = 0
    started_at: NanoTime = field(default_factory=lambda: NanoTime(0))
    floors: dict[str, float] = field(default_factory=lambda: dict(DEFAULT_FLOORS))
    rollovers: int = 0

    def __post_init__(self) -> None:
        check_anchor(self.anchor_day)
        if self.allowance_bytes <= 0:
            raise ShipError("allowance must be positive", detail=str(self.allowance_bytes))
        for name, floor in self.floors.items():
            if not 0.0 <= floor < 1.0:
                raise ShipError("a class floor must be in [0, 1)", detail=name)

    @property
    def remaining(self) -> int:
        return max(0, self.allowance_bytes - self.used_bytes)

    @property
    def spent_ratio(self) -> float:
        return self.used_bytes / self.allowance_bytes

    def floor_for(self, klass: str) -> float:
        return self.floors.get(klass, 0.0)

    def available_to(self, klass: str) -> int:
        """Bytes this class may still spend, given its floor."""
        reserved = int(self.allowance_bytes * self.floor_for(klass))
        return max(0, self.remaining - reserved)

    def allows(self, size: int, klass: str = "payment") -> bool:
        """True when a send of this size is inside what the class may spend."""
        if size < 0:
            raise ShipError("cannot meter a negative size", detail=str(size))
        return size <= self.available_to(klass)

    def charge(self, size: int) -> int:
        """Record a send. Returns the new total."""
        if size < 0:
            raise ShipError("cannot charge a negative size", detail=str(size))
        self.used_bytes += size
        return self.used_bytes

    def roll(self, now: NanoTime) -> bool:
        """Start a new period if this instant is in one. Returns whether it did."""
        start = period_start(now, self.anchor_day)
        if self.started_at.epoch_ns and start <= self.started_at:
            return False
        self.started_at = start
        self.used_bytes = 0
        self.rollovers += 1
        return True

    def ends_at(self) -> NanoTime:
        return period_end(self.started_at, self.anchor_day)

    def describe(self) -> str:
        return (
            f"meter {self.used_bytes}/{self.allowance_bytes} bytes "
            f"({100 * self.spent_ratio:.1f}%) this period"
        )
