package io.github.rkaushikdev.trammelworks.clockfix;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.NanoTime;
import org.junit.jupiter.api.Test;

class ClockfixDriftTest {

    static final String BOOT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d";
    static final long SECOND = NanoTime.NS_PER_SECOND;
    static final long MINUTE = 60 * SECOND;
    static final long HOUR = 3600 * SECOND;

    /** A sync where the clock was {@code errorSeconds} behind the truth. */
    static SyncEvent syncAt(long monotonicNs, double errorSeconds) {
        NanoTime truth = NanoTime.parse("2025-03-04T12:00:00Z");
        return new SyncEvent(BOOT, monotonicNs, truth.shifted(-(long) (errorSeconds * SECOND)), truth);
    }

    static DriftModel model(SyncEvent... syncs) {
        return new DriftModel(session(syncs));
    }

    static BootSession session(SyncEvent... syncs) {
        BootSession session = new BootSession(BOOT);
        for (SyncEvent event : syncs) {
            session.addSync(event);
        }
        return session;
    }

    @Test
    void aSessionWithNoSyncsCorrectsNothing() {
        Correction found = model().correctionAt(5 * MINUTE);
        assertThat(found.offsetNs()).isZero();
        assertThat(found.confidence()).isSameAs(Confidence.UNKNOWN);
        assertThat(found.trusted()).isFalse();
    }

    @Test
    void atTheMomentOfTheOnlySyncTheClockIsRight() {
        Correction found = model(syncAt(10 * MINUTE, 7)).correctionAt(10 * MINUTE);
        assertThat(found.offsetNs()).isZero();
        assertThat(found.confidence()).isSameAs(Confidence.MEASURED);
        assertThat(found.trusted()).isTrue();
    }

    @Test
    void beforeTheFirstSyncTheWholeMeasuredErrorApplies() {
        Correction found = model(syncAt(10 * MINUTE, 7)).correctionAt(2 * MINUTE);
        assertThat(found.offsetNs()).isEqualTo(7 * SECOND);
        assertThat(found.confidence()).isSameAs(Confidence.EXTRAPOLATED);
    }

    @Test
    void betweenTwoSyncsTheErrorIsSharedOut() {
        Correction found = model(syncAt(0, 0), syncAt(10 * MINUTE, 10)).correctionAt(5 * MINUTE);
        assertThat(found.offsetNs()).isEqualTo(5 * SECOND);
        assertThat(found.confidence()).isSameAs(Confidence.INTERPOLATED);
        assertThat(found.trusted()).isTrue();
    }

    @Test
    void justAfterASyncTheErrorIsAlmostNothing() {
        Correction found = model(syncAt(0, 0), syncAt(10 * MINUTE, 10)).correctionAt(1);
        assertThat(found.offsetNs()).isZero();
        assertThat(found.confidence()).isSameAs(Confidence.INTERPOLATED);
    }

    @Test
    void justBeforeTheNextSyncTheErrorIsAlmostAllOfIt() {
        // One nanosecond short of a ten minute span is 1 part in 6e11, which a double cannot hold,
        // so this lands on the full ten seconds rather than one nanosecond under it.
        Correction found = model(syncAt(0, 0), syncAt(10 * MINUTE, 10)).correctionAt(10 * MINUTE - 1);
        assertThat(Math.abs(found.offsetNs() - 10 * SECOND)).isLessThanOrEqualTo(1);
    }

    @Test
    void theInterpolationIsLinear() {
        DriftModel built = model(syncAt(0, 0), syncAt(100 * SECOND, 100));
        assertThat(built.correctionAt(25 * SECOND).offsetNs()).isEqualTo(25 * SECOND);
        assertThat(built.correctionAt(50 * SECOND).offsetNs()).isEqualTo(50 * SECOND);
    }

    @Test
    void twoSyncsAtTheSameInstantDoNotDivideByZero() {
        BootSession session = session(syncAt(MINUTE, 0), syncAt(MINUTE, 4));
        Correction found = new DriftModel(session).correctionAt(MINUTE);
        assertThat(found.confidence()).isIn(Confidence.MEASURED, Confidence.INTERPOLATED);
    }

    @Test
    void theRateComesFromTheLastPair() {
        assertThat(model(syncAt(0, 0), syncAt(100 * SECOND, 1)).rate()).hasValue(0.01);
    }

    @Test
    void theRateInPpmIsTheRateTimesAMillion() {
        assertThat(model(syncAt(0, 0), syncAt(1000 * SECOND, 1)).ratePpm()).hasValue(1000.0);
    }

    @Test
    void oneSyncGivesNoRate() {
        assertThat(model(syncAt(0, 3)).rate()).isEmpty();
        assertThat(model(syncAt(0, 3)).ratePpm()).isEmpty();
    }

    @Test
    void noSyncsGiveNoRate() {
        assertThat(model().rate()).isEmpty();
    }

    @Test
    void anImplausibleRateIsDiscarded() {
        assertThat(model(syncAt(0, 0), syncAt(SECOND, 1)).rate()).isEmpty();
    }

    @Test
    void afterTheLastSyncTheDriftIsProjected() {
        Correction found = model(syncAt(0, 0), syncAt(1000 * SECOND, 1)).correctionAt(2000 * SECOND);
        assertThat(found.offsetNs()).isEqualTo(SECOND);
        assertThat(found.confidence()).isSameAs(Confidence.EXTRAPOLATED);
        assertThat(found.trusted()).isFalse();
    }

    @Test
    void withOnlyOneSyncNothingIsProjected() {
        Correction found = model(syncAt(0, 7)).correctionAt(HOUR);
        assertThat(found.offsetNs()).isZero();
        assertThat(found.confidence()).isSameAs(Confidence.EXTRAPOLATED);
    }

    @Test
    void aProjectionInsideTheWindowIsNotStale() {
        assertThat(model(syncAt(0, 0), syncAt(1000 * SECOND, 1))
                        .correctionAt(2000 * SECOND)
                        .stale())
                .isFalse();
    }

    @Test
    void aProjectionPastTheWindowIsStale() {
        assertThat(model(syncAt(0, 0), syncAt(1000 * SECOND, 1))
                        .correctionAt(8 * HOUR)
                        .stale())
                .isTrue();
    }

    @Test
    void theStaleWindowCanBeNarrowed() {
        DriftModel built = new DriftModel(session(syncAt(0, 0), syncAt(1000 * SECOND, 1)), MINUTE);
        assertThat(built.correctionAt(1000 * SECOND + 2 * MINUTE).stale()).isTrue();
    }

    @Test
    void anInterpolatedCorrectionIsNeverStale() {
        DriftModel built = new DriftModel(session(syncAt(0, 0), syncAt(10 * HOUR, 1)), MINUTE);
        assertThat(built.correctionAt(5 * HOUR).stale()).isFalse();
    }

    @Test
    void aClockRunningFastIsCorrectedBackwards() {
        SyncEvent fast = new SyncEvent(
                BOOT, 10 * MINUTE, NanoTime.parse("2025-03-04T12:00:05Z"), NanoTime.parse("2025-03-04T12:00:00Z"));
        assertThat(model(fast).correctionAt(MINUTE).offsetNs()).isEqualTo(-5 * SECOND);
    }

    @Test
    void applyMovesAReadingByTheOffset() {
        NanoTime reading = NanoTime.parse("2025-03-04T11:59:53Z");
        assertThat(new Correction(7 * SECOND, Confidence.MEASURED).apply(reading))
                .isEqualTo(NanoTime.parse("2025-03-04T12:00:00Z"));
    }

    @Test
    void applyOfAZeroCorrectionChangesNothing() {
        NanoTime reading = NanoTime.parse("2025-03-04T12:00:00Z");
        assertThat(new Correction(0, Confidence.UNKNOWN).apply(reading)).isEqualTo(reading);
    }

    @Test
    void theErrorDropsToZeroAcrossASync() {
        // The point of the sawtooth: the sync removes the error, not some of it.
        DriftModel built = model(syncAt(10 * MINUTE, 10), syncAt(20 * MINUTE, 10));
        assertThat(built.correctionAt(10 * MINUTE - 1).offsetNs()).isEqualTo(10 * SECOND);
        assertThat(built.correctionAt(10 * MINUTE + 1).offsetNs()).isLessThan(SECOND);
    }
}
