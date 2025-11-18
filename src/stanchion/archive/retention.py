"""How long a record stays on the box, and what stops it going.

Three things decide, and they are checked in this order because each one can
overrule the ones after it:

1. **a legal hold.** A named hold covering a window keeps everything in that
   window, whatever else says. Holds are placed by the estate when a card scheme
   or a court asks, and nothing automatic may work around one.
2. **the class floor.** Payment records have a dispute window set by the card
   schemes, currently 180 days, and the site is not allowed to keep them for
   less. A config that tries is a config error rather than a shorter retention.
3. **the configured window.** Inside those bounds a site keeps what it wants.
   A busy motorway forecourt keeps 30 days of chatter; a rural site with a large
   card keeps a year.

The floors are the reason retention is not simply "delete older than N". A rule
that a site can shorten is not a floor, and a floor that the sweeper can ignore
is not a floor either, so both checks live here and the sweeper only ever asks
this module what it is allowed to delete.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import timedelta
from typing import Any

from stanchion.errors import ConfigError, RetentionError
from stanchion.timestamps import NS_PER_SECOND, NanoTime
from stanchion.units import format_duration, parse_duration

#: Card scheme dispute windows. A site may keep longer, never shorter.
CLASS_FLOORS: dict[str, timedelta] = {
    "payment": timedelta(days=180),
    "standard": timedelta(days=7),
    "chatter": timedelta(days=0),
}

DEFAULT_WINDOWS: dict[str, timedelta] = {
    "payment": timedelta(days=180),
    "standard": timedelta(days=30),
    "chatter": timedelta(days=7),
}


def floor_for(klass: str) -> timedelta:
    return CLASS_FLOORS.get(klass, timedelta(0))


def format_retention(window: timedelta) -> str:
    """Render a window in days.

    The general duration formatter reaches for weeks, and "25w5d" is not how
    anybody states a dispute window. Retention is talked about in days by the
    card schemes, by the estate and by the auditors, so it is written in days
    here even though the parser will happily read either.
    """
    seconds = int(window.total_seconds())
    if seconds and seconds % 86400 == 0:
        return f"{seconds // 86400}d"
    return format_duration(window)


@dataclass(frozen=True, slots=True)
class Hold:
    """A named reason not to delete something."""

    name: str
    reason: str
    placed_at: NanoTime
    covers_from: NanoTime | None = None
    covers_until: NanoTime | None = None

    def __post_init__(self) -> None:
        if not self.name.strip():
            raise RetentionError("a hold needs a name")
        if (
            self.covers_from is not None
            and self.covers_until is not None
            and self.covers_until < self.covers_from
        ):
            raise RetentionError("a hold cannot end before it starts", detail=self.name)

    @property
    def open_ended(self) -> bool:
        return self.covers_from is None and self.covers_until is None

    def covers(self, when: NanoTime) -> bool:
        """True when this instant is inside the hold."""
        if self.covers_from is not None and when < self.covers_from:
            return False
        if self.covers_until is not None and when > self.covers_until:
            return False
        return True


@dataclass(frozen=True, slots=True)
class RetentionPolicy:
    """The windows this site keeps, already checked against the floors."""

    windows: dict[str, timedelta] = field(default_factory=lambda: dict(DEFAULT_WINDOWS))

    def __post_init__(self) -> None:
        for klass, window in self.windows.items():
            if window < timedelta(0):
                raise ConfigError("a retention window cannot be negative", detail=klass)
            floor = floor_for(klass)
            if window < floor:
                raise ConfigError(
                    "a retention window is below the scheme floor",
                    detail=(f"{klass}: {format_retention(window)} < {format_retention(floor)}"),
                )

    def window_for(self, klass: str) -> timedelta:
        """How long this class is kept, never shorter than its floor."""
        return max(self.windows.get(klass, timedelta(0)), floor_for(klass))

    def cutoff_for(self, klass: str, now: NanoTime) -> NanoTime:
        """Anything older than this may go, holds permitting."""
        seconds = self.window_for(klass).total_seconds()
        return now.shifted(-int(seconds * NS_PER_SECOND))

    def describe(self) -> str:
        parts = [
            f"{klass} {format_retention(self.window_for(klass))}" for klass in sorted(self.windows)
        ]
        return "retention " + ", ".join(parts)


@dataclass(slots=True)
class RetentionDecision:
    """Whether one record may be deleted, and why not if it may not."""

    deletable: bool
    reason: str = ""
    hold: str = ""


def decide(
    policy: RetentionPolicy,
    holds: list[Hold],
    klass: str,
    when: NanoTime,
    now: NanoTime,
) -> RetentionDecision:
    """The whole question, in the order that matters."""
    for hold in holds:
        if hold.covers(when):
            return RetentionDecision(deletable=False, reason="held", hold=hold.name)
    cutoff = policy.cutoff_for(klass, now)
    if when >= cutoff:
        return RetentionDecision(deletable=False, reason="inside the window")
    return RetentionDecision(deletable=True, reason="older than the window")


def policy_from_mapping(raw: dict[str, Any]) -> RetentionPolicy:
    """Build the policy from the config block."""
    if not isinstance(raw, dict):
        raise ConfigError("the retention section must be a mapping")
    windows = dict(DEFAULT_WINDOWS)
    for klass, value in raw.items():
        windows[str(klass)] = parse_duration(value)
    return RetentionPolicy(windows=windows)
