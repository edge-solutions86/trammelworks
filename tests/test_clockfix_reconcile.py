from __future__ import annotations

from stanchion.clockfix.drift import Confidence
from stanchion.clockfix.reconcile import Reconciler
from stanchion.clockfix.session import SessionLedger, SyncEvent
from stanchion.clocks import ManualClock
from stanchion.records import LogRecord, Stream
from stanchion.timestamps import NS_PER_SECOND, NanoTime

BOOT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d"
MINUTE = 60 * NS_PER_SECOND
TRUTH = "2025-03-04T12:00:00Z"


def record(at: str, **over: object) -> LogRecord:
    base: dict[str, object] = {
        "site": "US-TX-0417",
        "unit": "payment-kernel",
        "stream": Stream.STDOUT,
        "observed_at": NanoTime.parse(at),
        "message": "auth approved",
    }
    base.update(over)
    return LogRecord(**base)  # type: ignore[arg-type]


def sync_at(monotonic_ns: int, error_seconds: float) -> SyncEvent:
    truth = NanoTime.parse(TRUTH)
    return SyncEvent(
        boot_id=BOOT,
        monotonic_ns=monotonic_ns,
        before=truth.shifted(-int(error_seconds * NS_PER_SECOND)),
        after=truth,
    )


def build(*syncs: SyncEvent, wall: str = TRUTH, monotonic: int = 20 * MINUTE) -> Reconciler:
    ledger = SessionLedger()
    for event in syncs:
        ledger.add_sync(event)
    ledger.session(BOOT)
    clock = ManualClock()
    clock.set_wall(wall)
    clock.monotonic = monotonic
    return Reconciler(ledger=ledger, boot_id=BOOT, clock=clock)


def test_a_session_with_no_syncs_leaves_the_time_alone() -> None:
    out = build().reconcile(record(TRUTH))
    assert out.corrected_at == out.observed_at
    assert out.clock_confidence == Confidence.UNKNOWN.value


def test_the_raw_reading_is_never_overwritten() -> None:
    original = record("2025-03-04T11:59:53Z")
    out = build(sync_at(15 * MINUTE, 7)).reconcile(original)
    assert out.observed_at == original.observed_at


def test_a_record_before_the_first_sync_gets_the_measured_error() -> None:
    out = build(sync_at(15 * MINUTE, 7)).reconcile(record("2025-03-04T11:50:00Z"))
    assert out.corrected_at is not None
    assert out.corrected_at - out.observed_at == 7 * NS_PER_SECOND
    assert out.clock_confidence == Confidence.EXTRAPOLATED.value


def test_a_record_between_two_syncs_is_interpolated() -> None:
    reconciler = build(sync_at(0, 0), sync_at(20 * MINUTE, 10), monotonic=20 * MINUTE)
    out = reconciler.reconcile(record("2025-03-04T11:50:00Z"))
    assert out.clock_confidence == Confidence.INTERPOLATED.value
    assert out.corrected_at is not None
    assert out.corrected_at > out.observed_at


def test_the_position_is_estimated_from_the_gap_to_now() -> None:
    reconciler = build(wall=TRUTH, monotonic=20 * MINUTE)
    position = reconciler.position_of(NanoTime.parse("2025-03-04T11:55:00Z"))
    assert position == 15 * MINUTE


def test_a_reading_from_before_the_boot_clamps_at_zero() -> None:
    reconciler = build(wall=TRUTH, monotonic=MINUTE)
    assert reconciler.position_of(NanoTime.parse("2020-01-01T00:00:00Z")) == 0


def test_a_reading_in_the_future_sits_past_now() -> None:
    reconciler = build(wall=TRUTH, monotonic=MINUTE)
    assert reconciler.position_of(NanoTime.parse("2025-03-04T12:00:30Z")) > MINUTE


def test_an_explicit_monotonic_label_is_used_instead_of_the_estimate() -> None:
    reconciler = build(sync_at(0, 0), sync_at(20 * MINUTE, 10))
    tagged = record(TRUTH, labels={"monotonic_ns": str(10 * MINUTE)})
    out = reconciler.reconcile(tagged)
    assert out.corrected_at is not None
    assert out.corrected_at - out.observed_at == 5 * NS_PER_SECOND
    assert reconciler.stats.from_monotonic == 1


def test_the_estimate_is_counted_separately_from_the_real_reading() -> None:
    reconciler = build(sync_at(15 * MINUTE, 7))
    reconciler.reconcile(record(TRUTH))
    assert reconciler.stats.from_monotonic == 0
    assert reconciler.stats.seen == 1


def test_the_boot_id_is_stamped_on_every_record() -> None:
    out = build().reconcile(record(TRUTH))
    assert out.labels["boot_id"] == BOOT


def test_the_drift_is_labelled_in_milliseconds() -> None:
    out = build(sync_at(15 * MINUTE, 7)).reconcile(record("2025-03-04T11:50:00Z"))
    assert out.labels["clock_drift_ms"] == "7000"


def test_a_zero_correction_leaves_no_drift_label() -> None:
    out = build().reconcile(record(TRUTH))
    assert "clock_drift_ms" not in out.labels


def test_a_stale_projection_is_labelled() -> None:
    reconciler = build(
        sync_at(0, 0), sync_at(1000 * NS_PER_SECOND, 1), monotonic=40 * 3600 * NS_PER_SECOND
    )
    out = reconciler.reconcile(record(TRUTH))
    assert out.labels["clock_stale"] == "true"
    assert reconciler.stats.stale == 1


def test_existing_labels_survive() -> None:
    out = build().reconcile(record(TRUTH, labels={"lane": "3"}))
    assert out.labels["lane"] == "3"


def test_best_at_prefers_the_correction() -> None:
    out = build(sync_at(15 * MINUTE, 7)).reconcile(record("2025-03-04T11:50:00Z"))
    assert out.best_at == out.corrected_at


def test_best_at_falls_back_to_the_raw_reading() -> None:
    raw = record(TRUTH)
    assert raw.best_at == raw.observed_at


def test_as_dict_carries_both_times() -> None:
    out = build(sync_at(15 * MINUTE, 7)).reconcile(record("2025-03-04T11:50:00Z"))
    payload = out.as_dict()
    assert payload["observed_at"] == "2025-03-04T11:50:00.000000000Z"
    assert payload["corrected_at"] == "2025-03-04T11:50:07.000000000Z"
    assert payload["clock_confidence"] == "extrapolated"


def test_as_dict_omits_a_correction_that_was_never_made() -> None:
    assert "corrected_at" not in record(TRUTH).as_dict()


def test_the_stats_add_up_across_a_batch() -> None:
    reconciler = build(sync_at(0, 0), sync_at(20 * MINUTE, 10))
    reconciler.reconcile_all([record(TRUTH) for _ in range(4)])
    assert reconciler.stats.seen == 4
    assert reconciler.stats.measured + reconciler.stats.interpolated == 4


def test_reconcile_all_keeps_the_order() -> None:
    reconciler = build()
    batch = [record(TRUTH, message=str(index)) for index in range(3)]
    assert [r.message for r in reconciler.reconcile_all(batch)] == ["0", "1", "2"]


def test_an_unknown_confidence_is_counted() -> None:
    reconciler = build()
    reconciler.reconcile(record(TRUTH))
    assert reconciler.stats.unknown == 1
    assert reconciler.stats.corrected == 0
