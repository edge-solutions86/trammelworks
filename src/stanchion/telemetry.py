"""Counters and gauges.

There is no scrape endpoint on a forecourt box, so metrics are not a monitoring
system here. They are a paragraph at the end of the nightly summary, and the
whole point is that the numbers add up: fragments read minus fragments joined
should equal records produced, and when it does not, that is the bug report.

Names are checked because the summary is parsed by the estate side and a typo
silently creates a second series that nobody totals.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from stanchion.errors import StanchionError

NAME_PATTERN = re.compile(r"^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")


def check_name(name: str) -> str:
    if not NAME_PATTERN.match(name):
        raise StanchionError(f"malformed metric name {name!r}")
    return name


@dataclass(slots=True)
class Counter:
    """A number that only goes up, reset only by a restart."""

    name: str
    value: int = 0

    def inc(self, amount: int = 1) -> None:
        if amount < 0:
            raise StanchionError(f"counter {self.name} cannot decrease")
        self.value += amount


@dataclass(slots=True)
class Gauge:
    """A number that reflects the present, such as spool bytes in use."""

    name: str
    value: int = 0

    def set(self, value: int) -> None:
        self.value = value

    def add(self, delta: int) -> None:
        self.value += delta


@dataclass(slots=True)
class Registry:
    """The set of series one process reports."""

    counters: dict[str, Counter] = field(default_factory=dict)
    gauges: dict[str, Gauge] = field(default_factory=dict)

    def counter(self, name: str) -> Counter:
        check_name(name)
        if name in self.gauges:
            raise StanchionError(f"{name} is already a gauge")
        existing = self.counters.get(name)
        if existing is None:
            existing = Counter(name)
            self.counters[name] = existing
        return existing

    def gauge(self, name: str) -> Gauge:
        check_name(name)
        if name in self.counters:
            raise StanchionError(f"{name} is already a counter")
        existing = self.gauges.get(name)
        if existing is None:
            existing = Gauge(name)
            self.gauges[name] = existing
        return existing

    def incr(self, name: str, amount: int = 1) -> None:
        self.counter(name).inc(amount)

    def snapshot(self) -> dict[str, int]:
        merged = {name: c.value for name, c in self.counters.items()}
        merged.update({name: g.value for name, g in self.gauges.items()})
        return dict(sorted(merged.items()))

    def render(self) -> str:
        """One ``name value`` pair per line, sorted, for the nightly summary."""
        return "\n".join(f"{name} {value}" for name, value in self.snapshot().items())

    def reset(self) -> None:
        self.counters.clear()
        self.gauges.clear()
