from __future__ import annotations

from stanchion.clockfix.drift import Confidence, Correction, DriftModel
from stanchion.clockfix.session import BootSession, SyncEvent
from stanchion.timestamps import NS_PER_SECOND, NanoTime

BOOT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d"
MINUTE = 60 * NS_PER_SECOND
HOUR = 3600 * NS_PER_SECOND


def sync_at(monotonic_ns: int, error_seconds: float) -> SyncEvent:
    """A sync where the clock was ``error_seconds`` behind the truth."""
    truth = NanoTime.parse("2025-03-04T12:00:00Z")
    return SyncEvent(
        boot_id=BOOT,
        monotonic_ns=monotonic_ns,
        before=truth.shifted(-int(error_seconds * NS_PER_SECOND)),
        after=truth,
    )


def model(*syncs: SyncEvent, stale_after_ns: int | None = None) -> DriftModel:
    session = BootSession(boot_id=BOOT)
    for event in syncs:
        session.add_sync(event)
    if stale_after_ns is None:
        return DriftModel(session=session)
    return DriftModel(session=session, stale_after_ns=stale_after_ns)


def test_a_session_with_no_syncs_corrects_nothing() -> None:
    found = model().correction_at(5 * MINUTE)
    assert found.offset_ns == 0
    assert found.confidence is Confidence.UNKNOWN
    assert found.trusted is False


def test_at_the_moment_of_the_only_sync_the_clock_is_right() -> None:
    found = model(sync_at(10 * MINUTE, 7)).correction_at(10 * MINUTE)
    assert found.offset_ns == 0
    assert found.confidence is Confidence.MEASURED
    assert found.trusted is True


def test_before_the_first_sync_the_whole_measured_error_applies() -> None:
    found = model(sync_at(10 * MINUTE, 7)).correction_at(2 * MINUTE)
    assert found.offset_ns == 7 * NS_PER_SECOND
    assert found.confidence is Confidence.EXTRAPOLATED


def test_between_two_syncs_the_error_is_shared_out() -> None:
    found = model(sync_at(0, 0), sync_at(10 * MINUTE, 10)).correction_at(5 * MINUTE)
    assert found.offset_ns == 5 * NS_PER_SECOND
    assert found.confidence is Confidence.INTERPOLATED
    assert found.trusted is True


def test_just_after_a_sync_the_error_is_almost_nothing() -> None:
    found = model(sync_at(0, 0), sync_at(10 * MINUTE, 10)).correction_at(1)
    assert found.offset_ns == 0
    assert found.confidence is Confidence.INTERPOLATED


def test_just_before_the_next_sync_the_error_is_almost_all_of_it() -> None:
    # One nanosecond short of a ten minute span is 1 part in 6e11, which a
    # double cannot hold, so this lands on the full ten seconds rather than one
    # nanosecond under it. Worth a tolerance rather than a wrong assertion.
    found = model(sync_at(0, 0), sync_at(10 * MINUTE, 10)).correction_at(10 * MINUTE - 1)
    assert abs(found.offset_ns - 10 * NS_PER_SECOND) <= 1


def test_the_interpolation_is_linear() -> None:
    built = model(sync_at(0, 0), sync_at(100 * NS_PER_SECOND, 100))
    quarter = built.correction_at(25 * NS_PER_SECOND)
    half = built.correction_at(50 * NS_PER_SECOND)
    assert quarter.offset_ns == 25 * NS_PER_SECOND
    assert half.offset_ns == 50 * NS_PER_SECOND


def test_two_syncs_at_the_same_instant_do_not_divide_by_zero() -> None:
    first = sync_at(MINUTE, 0)
    second = sync_at(MINUTE, 4)
    session = BootSession(boot_id=BOOT)
    session.syncs.extend([first, second])
    found = DriftModel(session=session).correction_at(MINUTE)
    assert found.confidence in (Confidence.MEASURED, Confidence.INTERPOLATED)


def test_the_rate_comes_from_the_last_pair() -> None:
    built = model(sync_at(0, 0), sync_at(100 * NS_PER_SECOND, 1))
    rate = built.rate()
    assert rate is not None
    assert rate == 0.01


def test_the_rate_in_ppm_is_the_rate_times_a_million() -> None:
    built = model(sync_at(0, 0), sync_at(1000 * NS_PER_SECOND, 1))
    assert built.rate_ppm() == 1000.0


def test_one_sync_gives_no_rate() -> None:
    assert model(sync_at(0, 3)).rate() is None
    assert model(sync_at(0, 3)).rate_ppm() is None


def test_no_syncs_give_no_rate() -> None:
    assert model().rate() is None


def test_an_implausible_rate_is_discarded() -> None:
    built = model(sync_at(0, 0), sync_at(NS_PER_SECOND, 1))
    assert built.rate() is None


def test_after_the_last_sync_the_drift_is_projected() -> None:
    built = model(sync_at(0, 0), sync_at(1000 * NS_PER_SECOND, 1))
    found = built.correction_at(2000 * NS_PER_SECOND)
    assert found.offset_ns == NS_PER_SECOND
    assert found.confidence is Confidence.EXTRAPOLATED
    assert found.trusted is False


def test_with_only_one_sync_nothing_is_projected() -> None:
    found = model(sync_at(0, 7)).correction_at(HOUR)
    assert found.offset_ns == 0
    assert found.confidence is Confidence.EXTRAPOLATED


def test_a_projection_inside_the_window_is_not_stale() -> None:
    built = model(sync_at(0, 0), sync_at(1000 * NS_PER_SECOND, 1))
    assert built.correction_at(2000 * NS_PER_SECOND).stale is False


def test_a_projection_past_the_window_is_stale() -> None:
    built = model(sync_at(0, 0), sync_at(1000 * NS_PER_SECOND, 1))
    assert built.correction_at(8 * HOUR).stale is True


def test_the_stale_window_can_be_narrowed() -> None:
    built = model(sync_at(0, 0), sync_at(1000 * NS_PER_SECOND, 1), stale_after_ns=MINUTE)
    assert built.correction_at(1000 * NS_PER_SECOND + 2 * MINUTE).stale is True


def test_an_interpolated_correction_is_never_stale() -> None:
    built = model(sync_at(0, 0), sync_at(10 * HOUR, 1), stale_after_ns=MINUTE)
    assert built.correction_at(5 * HOUR).stale is False


def test_a_clock_running_fast_is_corrected_backwards() -> None:
    fast = SyncEvent(
        boot_id=BOOT,
        monotonic_ns=10 * MINUTE,
        before=NanoTime.parse("2025-03-04T12:00:05Z"),
        after=NanoTime.parse("2025-03-04T12:00:00Z"),
    )
    found = model(fast).correction_at(MINUTE)
    assert found.offset_ns == -5 * NS_PER_SECOND


def test_apply_moves_a_reading_by_the_offset() -> None:
    reading = NanoTime.parse("2025-03-04T11:59:53Z")
    corrected = Correction(offset_ns=7 * NS_PER_SECOND, confidence=Confidence.MEASURED).apply(
        reading
    )
    assert corrected == NanoTime.parse("2025-03-04T12:00:00Z")


def test_apply_of_a_zero_correction_changes_nothing() -> None:
    reading = NanoTime.parse("2025-03-04T12:00:00Z")
    assert Correction(offset_ns=0, confidence=Confidence.UNKNOWN).apply(reading) == reading


def test_the_error_drops_to_zero_across_a_sync() -> None:
    """The point of the sawtooth: the sync removes the error, not some of it."""
    built = model(sync_at(10 * MINUTE, 10), sync_at(20 * MINUTE, 10))
    just_before = built.correction_at(10 * MINUTE - 1)
    just_after = built.correction_at(10 * MINUTE + 1)
    assert just_before.offset_ns == 10 * NS_PER_SECOND
    assert just_after.offset_ns < NS_PER_SECOND
