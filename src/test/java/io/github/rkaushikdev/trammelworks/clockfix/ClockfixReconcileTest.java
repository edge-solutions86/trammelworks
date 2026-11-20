package io.github.rkaushikdev.trammelworks.clockfix;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.ManualClock;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ClockfixReconcileTest {

    static final String BOOT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d";
    static final long SECOND = NanoTime.NS_PER_SECOND;
    static final long MINUTE = 60 * SECOND;
    static final String TRUTH = "2025-03-04T12:00:00Z";

    static LogRecord record(String at) {
        return new LogRecord("US-TX-0417", "payment-kernel", Stream.STDOUT, NanoTime.parse(at), "auth approved");
    }

    static SyncEvent syncAt(long monotonicNs, double errorSeconds) {
        NanoTime truth = NanoTime.parse(TRUTH);
        return new SyncEvent(BOOT, monotonicNs, truth.shifted(-(long) (errorSeconds * SECOND)), truth);
    }

    static Reconciler build(SyncEvent... syncs) {
        return build(TRUTH, 20 * MINUTE, syncs);
    }

    static Reconciler build(String wall, long monotonic, SyncEvent... syncs) {
        SessionLedger ledger = new SessionLedger();
        for (SyncEvent event : syncs) {
            ledger.addSync(event);
        }
        ledger.session(BOOT);
        ManualClock clock = new ManualClock();
        clock.setWall(wall);
        clock.setMonotonic(monotonic);
        return new Reconciler(ledger, BOOT, clock);
    }

    static long shift(LogRecord out) {
        return out.correctedAt().orElseThrow().minus(out.observedAt());
    }

    @Test
    void aSessionWithNoSyncsLeavesTheTimeAlone() {
        LogRecord out = build().reconcile(record(TRUTH));
        assertThat(out.correctedAt()).contains(out.observedAt());
        assertThat(out.clockConfidence()).isEqualTo(Confidence.UNKNOWN.value());
    }

    @Test
    void theRawReadingIsNeverOverwritten() {
        LogRecord original = record("2025-03-04T11:59:53Z");
        assertThat(build(syncAt(15 * MINUTE, 7)).reconcile(original).observedAt())
                .isEqualTo(original.observedAt());
    }

    @Test
    void aRecordBeforeTheFirstSyncGetsTheMeasuredError() {
        LogRecord out = build(syncAt(15 * MINUTE, 7)).reconcile(record("2025-03-04T11:50:00Z"));
        assertThat(shift(out)).isEqualTo(7 * SECOND);
        assertThat(out.clockConfidence()).isEqualTo(Confidence.EXTRAPOLATED.value());
    }

    @Test
    void aRecordBetweenTwoSyncsIsInterpolated() {
        Reconciler reconciler = build(TRUTH, 20 * MINUTE, syncAt(0, 0), syncAt(20 * MINUTE, 10));
        LogRecord out = reconciler.reconcile(record("2025-03-04T11:50:00Z"));
        assertThat(out.clockConfidence()).isEqualTo(Confidence.INTERPOLATED.value());
        assertThat(out.correctedAt().orElseThrow()).isGreaterThan(out.observedAt());
    }

    @Test
    void thePositionIsEstimatedFromTheGapToNow() {
        assertThat(build(TRUTH, 20 * MINUTE).positionOf(NanoTime.parse("2025-03-04T11:55:00Z")))
                .isEqualTo(15 * MINUTE);
    }

    @Test
    void aReadingFromBeforeTheBootClampsAtZero() {
        assertThat(build(TRUTH, MINUTE).positionOf(NanoTime.parse("2020-01-01T00:00:00Z")))
                .isZero();
    }

    @Test
    void aReadingInTheFutureSitsPastNow() {
        assertThat(build(TRUTH, MINUTE).positionOf(NanoTime.parse("2025-03-04T12:00:30Z")))
                .isGreaterThan(MINUTE);
    }

    @Test
    void anExplicitMonotonicLabelIsUsedInsteadOfTheEstimate() {
        Reconciler reconciler = build(syncAt(0, 0), syncAt(20 * MINUTE, 10));
        LogRecord tagged = record(TRUTH).replaceLabels(Map.of("monotonic_ns", Long.toString(10 * MINUTE)));
        LogRecord out = reconciler.reconcile(tagged);
        assertThat(shift(out)).isEqualTo(5 * SECOND);
        assertThat(reconciler.stats().fromMonotonic()).isEqualTo(1);
    }

    @Test
    void theEstimateIsCountedSeparatelyFromTheRealReading() {
        Reconciler reconciler = build(syncAt(15 * MINUTE, 7));
        reconciler.reconcile(record(TRUTH));
        assertThat(reconciler.stats().fromMonotonic()).isZero();
        assertThat(reconciler.stats().seen()).isEqualTo(1);
    }

    @Test
    void theBootIdIsStampedOnEveryRecord() {
        assertThat(build().reconcile(record(TRUTH)).labels().get("boot_id")).isEqualTo(BOOT);
    }

    @Test
    void theDriftIsLabelledInMilliseconds() {
        LogRecord out = build(syncAt(15 * MINUTE, 7)).reconcile(record("2025-03-04T11:50:00Z"));
        assertThat(out.labels().get("clock_drift_ms")).isEqualTo("7000");
    }

    @Test
    void aZeroCorrectionLeavesNoDriftLabel() {
        assertThat(build().reconcile(record(TRUTH)).labels()).doesNotContainKey("clock_drift_ms");
    }

    @Test
    void aStaleProjectionIsLabelled() {
        Reconciler reconciler = build(TRUTH, 40 * 3600 * SECOND, syncAt(0, 0), syncAt(1000 * SECOND, 1));
        LogRecord out = reconciler.reconcile(record(TRUTH));
        assertThat(out.labels().get("clock_stale")).isEqualTo("true");
        assertThat(reconciler.stats().stale()).isEqualTo(1);
    }

    @Test
    void existingLabelsSurvive() {
        LogRecord out = build().reconcile(record(TRUTH).replaceLabels(Map.of("lane", "3")));
        assertThat(out.labels().get("lane")).isEqualTo("3");
    }

    @Test
    void bestAtPrefersTheCorrection() {
        LogRecord out = build(syncAt(15 * MINUTE, 7)).reconcile(record("2025-03-04T11:50:00Z"));
        assertThat(out.bestAt()).isEqualTo(out.correctedAt().orElseThrow());
    }

    @Test
    void bestAtFallsBackToTheRawReading() {
        LogRecord raw = record(TRUTH);
        assertThat(raw.bestAt()).isEqualTo(raw.observedAt());
    }

    @Test
    void asDictCarriesBothTimes() {
        Map<String, Object> payload = build(syncAt(15 * MINUTE, 7))
                .reconcile(record("2025-03-04T11:50:00Z"))
                .asDict();
        assertThat(payload.get("observed_at")).isEqualTo("2025-03-04T11:50:00.000000000Z");
        assertThat(payload.get("corrected_at")).isEqualTo("2025-03-04T11:50:07.000000000Z");
        assertThat(payload.get("clock_confidence")).isEqualTo("extrapolated");
    }

    @Test
    void asDictOmitsACorrectionThatWasNeverMade() {
        assertThat(record(TRUTH).asDict()).doesNotContainKey("corrected_at");
    }

    @Test
    void theStatsAddUpAcrossABatch() {
        Reconciler reconciler = build(syncAt(0, 0), syncAt(20 * MINUTE, 10));
        reconciler.reconcileAll(
                IntStream.range(0, 4).mapToObj(index -> record(TRUTH)).toList());
        assertThat(reconciler.stats().seen()).isEqualTo(4);
        assertThat(reconciler.stats().measured() + reconciler.stats().interpolated())
                .isEqualTo(4);
    }

    @Test
    void reconcileAllKeepsTheOrder() {
        List<LogRecord> batch = IntStream.range(0, 3)
                .mapToObj(index -> record(TRUTH).withMessage(Integer.toString(index)))
                .toList();
        assertThat(build().reconcileAll(batch)).extracting(LogRecord::message).containsExactly("0", "1", "2");
    }

    @Test
    void anUnknownConfidenceIsCounted() {
        Reconciler reconciler = build();
        reconciler.reconcile(record(TRUTH));
        assertThat(reconciler.stats().unknown()).isEqualTo(1);
        assertThat(reconciler.stats().corrected()).isZero();
    }
}
