"""How long to wait before trying again.

Exponential with full jitter. The jitter is not decoration: an estate is
thousands of forecourts on the same collector, and a regional LTE outage brings
them all back at once. Without jitter they retry in lockstep and the first thing
the collector sees when it recovers is every site in the region at the same
instant, which knocks it over again. Full jitter, a uniform draw from zero to the
current ceiling, spreads that out better than the half jitter variants and is no
harder to reason about.

Outcomes are sorted into three kinds because they need three different answers:

* **retryable** — a timeout, a 503, a reset connection. Wait and try again.
* **terminal** — a 400, a 401, a payload the collector will not accept however
  many times it is offered. Retrying wastes metered bytes on a request that
  cannot succeed, so it is not retried and the batch is marked for a human.
* **throttled** — a 429 with a hint of how long to wait. The hint wins over our
  own arithmetic, since the collector knows what it is doing and we do not.

A site that has failed many times in a row goes into a cooldown longer than the
ordinary ceiling. On an LTE link that has genuinely gone away, one attempt every
few minutes costs nothing and finds the recovery just as fast as one every ten
seconds does.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from enum import StrEnum

from stanchion.errors import ShipError

DEFAULT_BASE_SECONDS = 2.0
DEFAULT_CEILING_SECONDS = 300.0
DEFAULT_MULTIPLIER = 2.0
DEFAULT_COOLDOWN_AFTER = 8
DEFAULT_COOLDOWN_SECONDS = 900.0


class Outcome(StrEnum):
    """What came back from an attempt."""

    OK = "ok"
    RETRYABLE = "retryable"
    TERMINAL = "terminal"
    THROTTLED = "throttled"


@dataclass(frozen=True, slots=True)
class BackoffPolicy:
    """The shape of the wait."""

    base_seconds: float = DEFAULT_BASE_SECONDS
    ceiling_seconds: float = DEFAULT_CEILING_SECONDS
    multiplier: float = DEFAULT_MULTIPLIER
    cooldown_after: int = DEFAULT_COOLDOWN_AFTER
    cooldown_seconds: float = DEFAULT_COOLDOWN_SECONDS

    def __post_init__(self) -> None:
        if self.base_seconds <= 0:
            raise ShipError("backoff base must be positive", detail=str(self.base_seconds))
        if self.ceiling_seconds < self.base_seconds:
            raise ShipError("backoff ceiling cannot be below the base")
        if self.multiplier <= 1.0:
            raise ShipError("backoff multiplier must be above one")
        if self.cooldown_after < 1:
            raise ShipError("cooldown_after must be at least one failure")
        if self.cooldown_seconds < self.ceiling_seconds:
            raise ShipError("a cooldown shorter than the ceiling achieves nothing")

    def ceiling_for(self, failures: int) -> float:
        """The unjittered wait after this many consecutive failures."""
        if failures <= 0:
            return 0.0
        if failures >= self.cooldown_after:
            return self.cooldown_seconds
        raw = self.base_seconds * (self.multiplier ** (failures - 1))
        return min(raw, self.ceiling_seconds)


@dataclass(slots=True)
class Backoff:
    """The state of one site's retrying."""

    policy: BackoffPolicy = field(default_factory=BackoffPolicy)
    failures: int = 0
    attempts: int = 0
    rng: random.Random = field(default_factory=random.Random)
    last_wait: float = 0.0

    @property
    def in_cooldown(self) -> bool:
        return self.failures >= self.policy.cooldown_after

    def succeed(self) -> None:
        """An attempt worked. Everything resets."""
        self.attempts += 1
        self.failures = 0
        self.last_wait = 0.0

    def fail(self, retry_after: float | None = None) -> float:
        """An attempt failed. Returns how long to wait.

        ``retry_after`` is the collector's own hint and it is taken as given
        rather than jittered, because the whole point of the hint is that every
        site waits until the collector says it is ready.
        """
        self.attempts += 1
        self.failures += 1
        if retry_after is not None:
            if retry_after < 0:
                raise ShipError("retry_after cannot be negative", detail=str(retry_after))
            self.last_wait = retry_after
            return self.last_wait
        ceiling = self.policy.ceiling_for(self.failures)
        self.last_wait = self.rng.uniform(0.0, ceiling)
        return self.last_wait

    def record(self, outcome: Outcome, retry_after: float | None = None) -> float:
        """Fold an outcome in. Returns the wait, which is zero when there is none."""
        if outcome is Outcome.OK:
            self.succeed()
            return 0.0
        if outcome is Outcome.TERMINAL:
            # Not a link problem. Counting it would push a healthy site into a
            # cooldown because one malformed batch keeps being refused.
            self.attempts += 1
            self.last_wait = 0.0
            return 0.0
        if outcome is Outcome.THROTTLED:
            return self.fail(retry_after)
        return self.fail(None)

    def reset(self) -> None:
        self.failures = 0
        self.last_wait = 0.0
