from __future__ import annotations

import random

import pytest

from stanchion.errors import ShipError
from stanchion.ship.backoff import Backoff, BackoffPolicy, Outcome

POLICY = BackoffPolicy(
    base_seconds=2.0,
    ceiling_seconds=60.0,
    multiplier=2.0,
    cooldown_after=5,
    cooldown_seconds=600.0,
)


def backoff(seed: int = 7, policy: BackoffPolicy | None = None) -> Backoff:
    return Backoff(policy=policy or POLICY, rng=random.Random(seed))


def test_a_non_positive_base_is_refused() -> None:
    with pytest.raises(ShipError, match="base must be positive"):
        BackoffPolicy(base_seconds=0)


def test_a_ceiling_below_the_base_is_refused() -> None:
    with pytest.raises(ShipError, match="ceiling cannot be below the base"):
        BackoffPolicy(base_seconds=10, ceiling_seconds=5)


def test_a_multiplier_of_one_is_refused() -> None:
    with pytest.raises(ShipError, match="multiplier must be above one"):
        BackoffPolicy(multiplier=1.0)


def test_a_cooldown_of_zero_failures_is_refused() -> None:
    with pytest.raises(ShipError, match="at least one failure"):
        BackoffPolicy(cooldown_after=0)


def test_a_cooldown_shorter_than_the_ceiling_is_refused() -> None:
    with pytest.raises(ShipError, match="achieves nothing"):
        BackoffPolicy(ceiling_seconds=300, cooldown_seconds=60)


def test_no_failures_means_no_wait() -> None:
    assert POLICY.ceiling_for(0) == 0.0


def test_the_ceiling_doubles_with_each_failure() -> None:
    assert POLICY.ceiling_for(1) == 2.0
    assert POLICY.ceiling_for(2) == 4.0
    assert POLICY.ceiling_for(3) == 8.0


def test_the_ceiling_stops_at_its_cap() -> None:
    tall = BackoffPolicy(base_seconds=2, ceiling_seconds=10, cooldown_after=50, cooldown_seconds=60)
    assert tall.ceiling_for(9) == 10.0


def test_enough_failures_reach_the_cooldown() -> None:
    assert POLICY.ceiling_for(5) == 600.0
    assert POLICY.ceiling_for(50) == 600.0


def test_a_wait_is_inside_its_ceiling() -> None:
    state = backoff()
    for expected in (2.0, 4.0, 8.0):
        assert 0.0 <= state.fail() <= expected


def test_the_wait_is_jittered_rather_than_fixed() -> None:
    first = backoff(seed=1)
    second = backoff(seed=2)
    for _ in range(3):
        first.fail()
        second.fail()
    assert first.last_wait != second.last_wait


def test_two_sites_with_the_same_seed_agree() -> None:
    assert backoff(seed=5).fail() == backoff(seed=5).fail()


def test_success_clears_the_failure_count() -> None:
    state = backoff()
    state.fail()
    state.fail()
    state.succeed()
    assert state.failures == 0
    assert state.last_wait == 0.0


def test_attempts_count_every_try() -> None:
    state = backoff()
    state.fail()
    state.succeed()
    assert state.attempts == 2


def test_the_cooldown_flag_follows_the_failures() -> None:
    state = backoff()
    for _ in range(4):
        state.fail()
    assert state.in_cooldown is False
    state.fail()
    assert state.in_cooldown is True


def test_a_success_leaves_the_cooldown() -> None:
    state = backoff()
    for _ in range(6):
        state.fail()
    state.succeed()
    assert state.in_cooldown is False


def test_a_retry_hint_is_taken_as_given() -> None:
    state = backoff()
    assert state.fail(retry_after=42.0) == 42.0


def test_a_retry_hint_is_not_jittered() -> None:
    left, right = backoff(seed=1), backoff(seed=2)
    assert left.fail(retry_after=30.0) == right.fail(retry_after=30.0)


def test_a_negative_retry_hint_is_refused() -> None:
    with pytest.raises(ShipError, match="cannot be negative"):
        backoff().fail(retry_after=-1)


def test_a_good_outcome_needs_no_wait() -> None:
    state = backoff()
    assert state.record(Outcome.OK) == 0.0


def test_a_retryable_outcome_waits() -> None:
    state = backoff()
    assert state.record(Outcome.RETRYABLE) > 0.0
    assert state.failures == 1


def test_a_throttled_outcome_uses_the_hint() -> None:
    state = backoff()
    assert state.record(Outcome.THROTTLED, retry_after=15.0) == 15.0


def test_a_throttled_outcome_without_a_hint_falls_back_to_the_curve() -> None:
    state = backoff()
    assert 0.0 <= state.record(Outcome.THROTTLED) <= 2.0


def test_a_terminal_outcome_does_not_wait_or_count_as_a_link_failure() -> None:
    state = backoff()
    assert state.record(Outcome.TERMINAL) == 0.0
    assert state.failures == 0
    assert state.attempts == 1


def test_terminal_outcomes_never_reach_the_cooldown() -> None:
    state = backoff()
    for _ in range(20):
        state.record(Outcome.TERMINAL)
    assert state.in_cooldown is False


def test_reset_clears_the_state_without_counting_an_attempt() -> None:
    state = backoff()
    state.fail()
    attempts = state.attempts
    state.reset()
    assert state.failures == 0
    assert state.attempts == attempts


def test_the_wait_after_a_recovery_starts_from_the_bottom_again() -> None:
    state = backoff()
    for _ in range(6):
        state.fail()
    state.succeed()
    assert state.fail() <= 2.0
