package io.github.rkaushikdev.trammelworks.clockfix;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClockfixObserveTest {

    static final String BOOT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d";
    static final String AT = "2025-03-04T12:00:00Z";
    static final long SECOND = NanoTime.NS_PER_SECOND;

    static LogRecord record(String message) {
        return record(message, "chronyd", 600L);
    }

    static LogRecord record(String message, String unit) {
        return record(message, unit, 600L);
    }

    static LogRecord record(String message, String unit, Long monotonic) {
        LogRecord base = new LogRecord("US-TX-0417", unit, Stream.STDOUT, NanoTime.parse(AT), message);
        return monotonic == null ? base : base.replaceLabels(Map.of("monotonic_ns", monotonic.toString()));
    }

    static Observation chrony(LogRecord line) {
        return SyncLines.observeChrony(line, BOOT).orElseThrow();
    }

    @Test
    void aClockThatWasBehindIsMovedForwards() {
        Observation found = chrony(record("System clock wrong by -7.000000 seconds, adjustment started"));
        assertThat(found.event().offsetNs()).isEqualTo(7 * SECOND);
        assertThat(found.event().forwards()).isTrue();
    }

    @Test
    void aClockThatWasAheadIsMovedBack() {
        Observation found = chrony(record("System clock wrong by 3.500000 seconds, adjustment started"));
        assertThat(found.event().offsetNs()).isEqualTo(-3_500_000_000L);
        assertThat(found.event().forwards()).isFalse();
    }

    @Test
    void theBeforeReadingIsWhatTheClockSaid() {
        assertThat(chrony(record("System clock wrong by -7.0 seconds")).event().before())
                .isEqualTo(NanoTime.parse(AT));
    }

    @Test
    void theAfterReadingIsTheTruth() {
        assertThat(chrony(record("System clock wrong by -7.0 seconds")).event().after())
                .isEqualTo(NanoTime.parse("2025-03-04T12:00:07Z"));
    }

    @Test
    void aWholeNumberOfSecondsIsRead() {
        assertThat(chrony(record("System clock wrong by -2 seconds")).event().offsetNs())
                .isEqualTo(2 * SECOND);
    }

    @Test
    void aPlusSignIsAccepted() {
        assertThat(chrony(record("System clock wrong by +1.0 seconds")).event().offsetNs())
                .isEqualTo(-SECOND);
    }

    @Test
    void theMonotonicLabelBecomesThePosition() {
        assertThat(chrony(record("System clock wrong by -1.0 seconds", "chronyd", 4096L))
                        .event()
                        .monotonicNs())
                .isEqualTo(4096);
    }

    @Test
    void aChronyLineWithoutAPositionIsNotUsable() {
        assertThat(SyncLines.observeChrony(record("System clock wrong by -7.0 seconds", "chronyd", null), BOOT))
                .isEmpty();
    }

    @Test
    void anUnrelatedChronyLineIsIgnored() {
        assertThat(SyncLines.observeChrony(record("Selected source 10.0.0.1"), BOOT))
                .isEmpty();
    }

    @Test
    void aChronyEventIsMarkedExact() {
        assertThat(chrony(record("System clock wrong by -1.0 seconds")).exact()).isTrue();
    }

    @Test
    void timesyncdInitialSyncIsRecognised() {
        LogRecord line =
                record("Initial synchronization to time server 10.0.0.1:123 (ntp.internal).", "systemd-timesyncd");
        assertThat(SyncLines.observeTimesyncd(line, BOOT).orElseThrow().event().source())
                .isEqualTo("timesyncd");
    }

    @Test
    void aLaterTimesyncdSyncIsRecognised() {
        LogRecord line = record("Synchronization to time server 10.0.0.1:123.", "systemd-timesyncd");
        assertThat(SyncLines.observeTimesyncd(line, BOOT)).isPresent();
    }

    @Test
    void aTimesyncdEventMeasuresNothing() {
        LogRecord line = record("Initial synchronization to time server 10.0.0.1:123.", "systemd-timesyncd");
        Observation found = SyncLines.observeTimesyncd(line, BOOT).orElseThrow();
        assertThat(found.event().offsetNs()).isZero();
        assertThat(found.exact()).isFalse();
    }

    @Test
    void anUnrelatedTimesyncdLineIsIgnored() {
        assertThat(SyncLines.observeTimesyncd(record("Timed out waiting for reply", "systemd-timesyncd"), BOOT))
                .isEmpty();
    }

    @Test
    void truthAtSubtractsTheError() {
        assertThat(SyncLines.truthAt(record("x"), -7.0)).isEqualTo(NanoTime.parse("2025-03-04T12:00:07Z"));
        assertThat(SyncLines.truthAt(record("x"), 7.0)).isEqualTo(NanoTime.parse("2025-03-04T11:59:53Z"));
    }

    static SyncObserver observer() {
        return new SyncObserver(new SessionLedger(), BOOT);
    }

    @Test
    void theObserverFilesAChronyEvent() {
        SyncObserver watcher = observer();
        watcher.look(record("System clock wrong by -7.0 seconds"));
        assertThat(watcher.filed()).isEqualTo(1);
        assertThat(watcher.ledger().session(BOOT).synced()).isTrue();
    }

    @Test
    void theObserverIgnoresOtherUnits() {
        SyncObserver watcher = observer();
        assertThat(watcher.look(record("System clock wrong by -7.0 seconds", "pump-bridge")))
                .isEmpty();
        assertThat(watcher.seen()).isZero();
    }

    @Test
    void theObserverCountsWhatItLookedAt() {
        SyncObserver watcher = observer();
        watcher.look(record("Selected source 10.0.0.1"));
        assertThat(watcher.seen()).isEqualTo(1);
        assertThat(watcher.filed()).isZero();
    }

    @Test
    void theObserverNotesASyncItCouldNotPlace() {
        SyncObserver watcher = observer();
        watcher.look(record("System clock wrong by -7.0 seconds", "chronyd", null));
        assertThat(watcher.ignoredNoPosition()).isEqualTo(1);
        assertThat(watcher.filed()).isZero();
    }

    @Test
    void anUnplaceableNonSyncLineIsNotCountedAsLost() {
        SyncObserver watcher = observer();
        watcher.look(record("Selected source 10.0.0.1", "chronyd", null));
        assertThat(watcher.ignoredNoPosition()).isZero();
    }

    @Test
    void theBootLabelWinsOverTheDefault() {
        LogRecord tagged = record("System clock wrong by -7.0 seconds").withLabel("boot_id", "aa".repeat(16));
        assertThat(observer().look(tagged)).map(Observation::bootId).contains("aa".repeat(16));
    }

    @Test
    void aMalformedBootLabelIsRefusedQuietly() {
        SyncObserver watcher = observer();
        LogRecord line = record("System clock wrong by -7.0 seconds").withLabel("boot_id", "nonsense");
        assertThat(watcher.look(line)).isEmpty();
        assertThat(watcher.filed()).isZero();
    }

    @Test
    void lookAllReturnsOnlyTheHits() {
        SyncObserver watcher = observer();
        List<Observation> found = watcher.lookAll(List.of(
                record("System clock wrong by -1.0 seconds"),
                record("Selected source 10.0.0.1"),
                record("System clock wrong by -2.0 seconds")));
        assertThat(found).hasSize(2);
        assertThat(watcher.filed()).isEqualTo(2);
    }

    @Test
    void twoEventsLandInTheSameSessionInOrder() {
        SyncObserver watcher = observer();
        watcher.look(record("System clock wrong by -1.0 seconds", "chronyd", 900L));
        watcher.look(record("System clock wrong by -2.0 seconds", "chronyd", 100L));
        assertThat(watcher.ledger().session(BOOT).syncs())
                .extracting(SyncEvent::monotonicNs)
                .containsExactly(100L, 900L);
    }

    @Test
    void aTimesyncdLineIsFiledToo() {
        SyncObserver watcher = observer();
        watcher.look(record("Initial synchronization to time server 10.0.0.1:123.", "systemd-timesyncd"));
        assertThat(watcher.filed()).isEqualTo(1);
    }
}
