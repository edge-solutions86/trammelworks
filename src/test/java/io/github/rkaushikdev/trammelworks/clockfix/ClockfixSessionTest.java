package io.github.rkaushikdev.trammelworks.clockfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.NanoTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClockfixSessionTest {

    static final String BOOT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d";
    static final String OTHER = "aa".repeat(16);
    static final long SECOND = NanoTime.NS_PER_SECOND;
    static final NanoTime BEFORE = NanoTime.parse("2025-03-04T11:29:53Z");
    static final NanoTime AFTER = NanoTime.parse("2025-03-04T11:30:00Z");

    static SyncEvent sync() {
        return sync(60 * SECOND);
    }

    static SyncEvent sync(long monotonicNs) {
        return new SyncEvent(BOOT, monotonicNs, BEFORE, AFTER);
    }

    static SyncEvent sync(String before, String after) {
        return new SyncEvent(BOOT, 60 * SECOND, NanoTime.parse(before), NanoTime.parse(after));
    }

    @Test
    void parseBootIdAcceptsPlainHex() {
        assertThat(BootSession.parseBootId(BOOT)).isEqualTo(BOOT);
    }

    @Test
    void parseBootIdStripsDashesAndCase() {
        assertThat(BootSession.parseBootId("4C1F2E3D-4C1F-2E3D-4C1F-2E3D4C1F2E3D"))
                .isEqualTo(BOOT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", "4c1f", "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d"})
    void parseBootIdRejectsAnythingElse(String raw) {
        assertThatThrownBy(() -> BootSession.parseBootId(raw))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("malformed boot id");
    }

    @Test
    void offsetIsWhatTheClockWasMissing() {
        assertThat(sync().offsetNs()).isEqualTo(7 * SECOND);
    }

    @Test
    void aClockThatRanFastGivesANegativeOffset() {
        SyncEvent event = sync("2025-03-04T11:30:05Z", "2025-03-04T11:30:00Z");
        assertThat(event.offsetNs()).isEqualTo(-5 * SECOND);
        assertThat(event.forwards()).isFalse();
    }

    @Test
    void aClockThatRanSlowIsMovedForwards() {
        assertThat(sync().forwards()).isTrue();
    }

    @Test
    void aSmallCorrectionIsNotLarge() {
        assertThat(sync().large()).isFalse();
    }

    @Test
    void anHourOfErrorCountsAsLarge() {
        assertThat(sync("2025-03-04T11:29:53Z", "2025-03-04T12:30:00Z").large()).isTrue();
    }

    @Test
    void aLargeCorrectionBackwardsAlsoCounts() {
        assertThat(sync("2025-03-04T14:00:00Z", "2025-03-04T11:30:00Z").large()).isTrue();
    }

    @Test
    void aSyncNormalizesItsBootId() {
        assertThat(new SyncEvent(BOOT.toUpperCase(Locale.ROOT), 60 * SECOND, BEFORE, AFTER).bootId())
                .isEqualTo(BOOT);
    }

    @Test
    void aSyncRefusesANegativeMonotonic() {
        assertThatThrownBy(() -> sync(-1)).isInstanceOf(ConfigError.class).hasMessageContaining("negative monotonic");
    }

    @Test
    void theSourceDefaultsToNtp() {
        assertThat(sync().source()).isEqualTo("ntp");
    }

    @Test
    void aSessionStartsUnsynced() {
        BootSession session = new BootSession(BOOT);
        assertThat(session.synced()).isFalse();
        assertThat(session.syncs()).isEmpty();
    }

    @Test
    void addingASyncMarksTheSessionSynced() {
        BootSession session = new BootSession(BOOT);
        session.addSync(sync());
        assertThat(session.synced()).isTrue();
    }

    @Test
    void aSessionRefusesASyncFromAnotherBoot() {
        BootSession session = new BootSession(BOOT);
        assertThatThrownBy(() -> session.addSync(new SyncEvent(OTHER, 60 * SECOND, BEFORE, AFTER)))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("belongs to another boot");
    }

    @Test
    void syncsAreKeptInMonotonicOrder() {
        BootSession session = new BootSession(BOOT);
        session.addSync(sync(300 * SECOND));
        session.addSync(sync(60 * SECOND));
        assertThat(session.syncs()).extracting(SyncEvent::monotonicNs).containsExactly(60 * SECOND, 300 * SECOND);
    }

    @Test
    void aSessionBuiltWithSyncsSortsThem() {
        BootSession session = new BootSession(BOOT, 0, 0, List.of(sync(300), sync(60)));
        assertThat(session.syncs()).extracting(SyncEvent::monotonicNs).containsExactly(60L, 300L);
    }

    @Test
    void aSessionBuiltWithAForeignSyncIsRefused() {
        assertThatThrownBy(() -> new BootSession(BOOT, 0, 0, List.of(new SyncEvent(OTHER, 60, BEFORE, AFTER))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("belongs to another boot");
    }

    @Test
    void observeTracksTheSpanOfTheSession() {
        BootSession session = new BootSession(BOOT);
        session.observe(100);
        session.observe(900);
        session.observe(400);
        assertThat(session.firstMonotonicNs()).isEqualTo(100);
        assertThat(session.lastMonotonicNs()).isEqualTo(900);
        assertThat(session.durationNs()).isEqualTo(800);
    }

    @Test
    void observeRefusesANegativeReading() {
        assertThatThrownBy(() -> new BootSession(BOOT).observe(-1))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("negative monotonic");
    }

    @Test
    void addingASyncAlsoObservesIt() {
        BootSession session = new BootSession(BOOT);
        session.addSync(sync(500));
        assertThat(session.lastMonotonicNs()).isEqualTo(500);
    }

    @Test
    void bracketOfAnUnsyncedSessionIsEmpty() {
        assertThat(new BootSession(BOOT).bracket(100))
                .isEqualTo(new BootSession.Bracket(Optional.empty(), Optional.empty()));
    }

    @Test
    void bracketFindsTheSyncBefore() {
        BootSession session = new BootSession(BOOT);
        SyncEvent first = sync(100);
        session.addSync(first);
        BootSession.Bracket found = session.bracket(500);
        assertThat(found.before()).containsSame(first);
        assertThat(found.after()).isEmpty();
    }

    @Test
    void bracketFindsTheSyncAfter() {
        BootSession session = new BootSession(BOOT);
        SyncEvent later = sync(900);
        session.addSync(later);
        BootSession.Bracket found = session.bracket(500);
        assertThat(found.before()).isEmpty();
        assertThat(found.after()).containsSame(later);
    }

    @Test
    void bracketFindsBoth() {
        BootSession session = new BootSession(BOOT);
        SyncEvent first = sync(100);
        SyncEvent second = sync(900);
        session.addSync(first);
        session.addSync(second);
        assertThat(session.bracket(500)).isEqualTo(new BootSession.Bracket(Optional.of(first), Optional.of(second)));
    }

    @Test
    void aPointExactlyOnASyncCountsAsAfterIt() {
        BootSession session = new BootSession(BOOT);
        SyncEvent event = sync(100);
        session.addSync(event);
        BootSession.Bracket found = session.bracket(100);
        assertThat(found.before()).containsSame(event);
        assertThat(found.after()).isEmpty();
    }

    @Test
    void theLedgerCreatesASessionOnFirstSight() {
        SessionLedger ledger = new SessionLedger();
        BootSession session = ledger.session(BOOT);
        assertThat(session.bootId()).isEqualTo(BOOT);
        assertThat(ledger.session(BOOT)).isSameAs(session);
    }

    @Test
    void theLedgerNormalizesTheBootId() {
        SessionLedger ledger = new SessionLedger();
        assertThat(ledger.session(BOOT)).isSameAs(ledger.session(BOOT.toUpperCase(Locale.ROOT)));
    }

    @Test
    void theCurrentSessionIsTheNewestBoot() {
        SessionLedger ledger = new SessionLedger();
        ledger.session(BOOT);
        ledger.session(OTHER);
        assertThat(ledger.current()).map(BootSession::bootId).contains(OTHER);
    }

    @Test
    void anEmptyLedgerHasNoCurrentSession() {
        assertThat(new SessionLedger().current()).isEmpty();
    }

    @Test
    void rebootsCountsTheGapsNotTheSessions() {
        SessionLedger ledger = new SessionLedger();
        assertThat(ledger.reboots()).isZero();
        ledger.session(BOOT);
        assertThat(ledger.reboots()).isZero();
        ledger.session(OTHER);
        assertThat(ledger.reboots()).isEqualTo(1);
    }

    @Test
    void theLedgerRoutesASyncToItsOwnSession() {
        SessionLedger ledger = new SessionLedger();
        ledger.addSync(sync());
        assertThat(ledger.session(BOOT).synced()).isTrue();
    }

    @Test
    void forgetDropsTheOldestSessions() {
        SessionLedger ledger = new SessionLedger();
        ledger.session(BOOT);
        ledger.session(OTHER);
        ledger.session("bb".repeat(16));
        assertThat(ledger.forgetAllBut(1)).isEqualTo(2);
        assertThat(ledger.current()).map(BootSession::bootId).contains("bb".repeat(16));
    }

    @Test
    void forgetKeepsEverythingWhenThereIsRoom() {
        SessionLedger ledger = new SessionLedger();
        ledger.session(BOOT);
        assertThat(ledger.forgetAllBut(4)).isZero();
    }

    @Test
    void forgetRefusesToKeepNothing() {
        assertThatThrownBy(() -> new SessionLedger().forgetAllBut(0))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("at least one session");
    }
}
