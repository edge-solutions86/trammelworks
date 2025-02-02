from __future__ import annotations

import pytest

from stanchion.clocks import Clock, Deadline, ManualClock, SystemClock
from stanchion.errors import StanchionError
from stanchion.timestamps import NS_PER_SECOND, NanoTime


def test_system_clock_satisfies_the_protocol() -> None:
    assert isinstance(SystemClock(), Clock)


def test_manual_clock_satisfies_the_protocol() -> None:
    assert isinstance(ManualClock(), Clock)


def test_system_clock_wall_reading_is_plausible() -> None:
    now = SystemClock().now()
    assert now > NanoTime.parse("2020-01-01T00:00:00Z")


def test_system_clock_monotonic_never_goes_backwards() -> None:
    clock = SystemClock()
    first = clock.monotonic_ns()
    second = clock.monotonic_ns()
    assert second >= first


def test_manual_clock_starts_at_the_epoch() -> None:
    clock = ManualClock()
    assert clock.now() == NanoTime(0)
    assert clock.monotonic_ns() == 0


def test_advance_moves_both_readings_together() -> None:
    clock = ManualClock()
    clock.advance(1.5)
    assert clock.now().epoch_ns == 1_500_000_000
    assert clock.monotonic_ns() == 1_500_000_000


def test_advance_accumulates() -> None:
    clock = ManualClock()
    clock.advance(1)
    clock.advance(2)
    assert clock.monotonic_ns() == 3 * NS_PER_SECOND


def test_advance_refuses_to_go_backwards() -> None:
    with pytest.raises(StanchionError, match="cannot advance backwards"):
        ManualClock().advance(-1)


def test_step_wall_leaves_the_monotonic_counter_alone() -> None:
    clock = ManualClock()
    clock.advance(10)
    clock.step_wall(-4)
    assert clock.now().epoch_ns == 6 * NS_PER_SECOND
    assert clock.monotonic_ns() == 10 * NS_PER_SECOND


def test_step_wall_can_jump_forwards_like_an_ntp_correction() -> None:
    clock = ManualClock()
    clock.step_wall(3600)
    assert clock.now().epoch_ns == 3600 * NS_PER_SECOND
    assert clock.monotonic_ns() == 0


def test_set_wall_accepts_a_nanotime() -> None:
    clock = ManualClock()
    clock.set_wall(NanoTime.parse("2025-03-04T11:30:00Z"))
    assert clock.now().isoformat() == "2025-03-04T11:30:00.000000000Z"


def test_set_wall_accepts_a_string() -> None:
    clock = ManualClock()
    clock.set_wall("2025-03-04T11:30:00.25Z")
    assert clock.now().nanos_in_second == 250000000


def test_deadline_after_is_relative_to_the_monotonic_reading() -> None:
    clock = ManualClock()
    clock.advance(100)
    deadline = Deadline.after(clock, 30)
    assert deadline.at_ns == 130 * NS_PER_SECOND


def test_deadline_refuses_a_negative_offset() -> None:
    with pytest.raises(StanchionError, match="cannot be in the past"):
        Deadline.after(ManualClock(), -1)


def test_deadline_is_not_expired_before_it_lands() -> None:
    clock = ManualClock()
    deadline = Deadline.after(clock, 10)
    clock.advance(9.9)
    assert deadline.expired(clock) is False


def test_deadline_expires_exactly_on_the_boundary() -> None:
    clock = ManualClock()
    deadline = Deadline.after(clock, 10)
    clock.advance(10)
    assert deadline.expired(clock) is True


def test_deadline_remaining_counts_down() -> None:
    clock = ManualClock()
    deadline = Deadline.after(clock, 10)
    clock.advance(4)
    assert deadline.remaining(clock) == pytest.approx(6.0)


def test_deadline_remaining_clamps_at_zero() -> None:
    clock = ManualClock()
    deadline = Deadline.after(clock, 1)
    clock.advance(30)
    assert deadline.remaining(clock) == 0.0


def test_deadline_ignores_a_wall_clock_jump() -> None:
    clock = ManualClock()
    deadline = Deadline.after(clock, 10)
    clock.step_wall(-86400)
    assert deadline.expired(clock) is False
    assert deadline.remaining(clock) == pytest.approx(10.0)


def test_deadline_is_hashable_and_comparable_by_value() -> None:
    assert Deadline(5) == Deadline(5)
    assert len({Deadline(5), Deadline(5)}) == 1
