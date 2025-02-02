"""Clock abstraction.

Two separate readings matter and they answer different questions. The wall clock
says what time the site controller thinks it is, which is a claim rather than a
fact because the RTC drifts and NTP only lands when the LTE modem is up. The
monotonic counter says how long since this boot, which is reliable but means
nothing across a restart.

Everything that has to be reasoned about later takes a ``Clock`` rather than
calling ``time`` directly, so that a test can drive it and a replay can be run
against recorded readings.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable

from stanchion.errors import StanchionError
from stanchion.timestamps import NS_PER_SECOND, NanoTime


@runtime_checkable
class Clock(Protocol):
    """What the pipeline is allowed to ask about time."""

    def now(self) -> NanoTime:
        """The current wall clock reading, which may be wrong."""

    def monotonic_ns(self) -> int:
        """Nanoseconds since an arbitrary point that only moves forward."""


class SystemClock:
    """The real clock, used everywhere outside tests."""

    __slots__ = ()

    def now(self) -> NanoTime:
        return NanoTime(time.time_ns())

    def monotonic_ns(self) -> int:
        return time.monotonic_ns()


@dataclass(slots=True)
class ManualClock:
    """A clock a test drives by hand.

    The two readings move independently on purpose. A site controller that gets
    an NTP correction jumps its wall clock while the monotonic counter carries
    on, and the drift code has to be exercised against exactly that.
    """

    wall: NanoTime = field(default_factory=lambda: NanoTime(0))
    monotonic: int = 0

    def now(self) -> NanoTime:
        return self.wall

    def monotonic_ns(self) -> int:
        return self.monotonic

    def advance(self, seconds: float) -> None:
        """Move both readings forward by the same amount, the ordinary case."""
        if seconds < 0:
            raise StanchionError("manual clock cannot advance backwards")
        delta = int(seconds * NS_PER_SECOND)
        self.wall = self.wall.shifted(delta)
        self.monotonic += delta

    def step_wall(self, seconds: float) -> None:
        """Jump the wall clock only, the way an NTP correction does."""
        self.wall = self.wall.shifted(int(seconds * NS_PER_SECOND))

    def set_wall(self, value: NanoTime | str) -> None:
        self.wall = NanoTime.parse(value) if isinstance(value, str) else value


@dataclass(frozen=True, slots=True)
class Deadline:
    """A monotonic deadline, used by the shipper to bound an attempt."""

    at_ns: int

    @classmethod
    def after(cls, clock: Clock, seconds: float) -> Deadline:
        if seconds < 0:
            raise StanchionError("deadline cannot be in the past")
        return cls(clock.monotonic_ns() + int(seconds * NS_PER_SECOND))

    def expired(self, clock: Clock) -> bool:
        return clock.monotonic_ns() >= self.at_ns

    def remaining(self, clock: Clock) -> float:
        """Seconds left, clamped at zero so a caller can pass it to a timeout."""
        left = self.at_ns - clock.monotonic_ns()
        return max(0.0, left / NS_PER_SECOND)
