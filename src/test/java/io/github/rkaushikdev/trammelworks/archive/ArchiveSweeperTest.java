package io.github.rkaushikdev.trammelworks.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.RetentionError;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveSweeperTest {

    static final NanoTime NOW = NanoTime.parse("2025-09-01T12:00:00Z");
    static final RetentionPolicy POLICY = new RetentionPolicy(
            Map.of("payment", Duration.ofDays(180), "standard", Duration.ofDays(30), "chatter", Duration.ofDays(7)));

    @TempDir
    Path tmp;

    ArchiveDb connection;
    Repository repo;

    @BeforeEach
    void setUp() {
        connection = Schema.openArchive(ArchiveFixtures.archivePath(tmp));
        repo = new Repository(connection, ArchiveFixtures.TABLE);
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    static NanoTime at(String raw) {
        return NanoTime.parse(raw);
    }

    void add(String unit, String when) {
        add(unit, when, "line");
    }

    void add(String unit, String when, String message) {
        repo.add(ArchiveFixtures.record(unit, at(when), message + " " + unit + " " + when), NOW);
    }

    Sweeper sweeper() {
        return new Sweeper(connection, POLICY, Sweeper.DEFAULT_MAX_DELETES);
    }

    Sweeper sweeper(int maxDeletes) {
        return new Sweeper(connection, POLICY, maxDeletes);
    }

    static Hold hold(String name, String reason) {
        return new Hold(name, reason, NOW);
    }

    @Test
    void aMaxOfZeroIsRefused() {
        assertThatThrownBy(() -> sweeper(0)).isInstanceOf(RetentionError.class).hasMessageContaining("at least one");
    }

    @Test
    void anEmptyArchiveSweepsNothing() {
        SweepReport report = sweeper().sweep(NOW);
        assertThat(report.deleted()).isZero();
        assertThat(report.examined()).isZero();
    }

    @Test
    void aRecentRecordSurvives() {
        add("price-sign", "2025-08-30T12:00:00Z");
        sweeper().sweep(NOW);
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void anExpiredChatterRecordGoes() {
        add("price-sign", "2025-08-01T12:00:00Z");
        SweepReport report = sweeper().sweep(NOW);
        assertThat(report.deleted()).isEqualTo(1);
        assertThat(repo.count()).isZero();
    }

    @Test
    void aPaymentRecordOfTheSameAgeStays() {
        add("price-sign", "2025-08-01T12:00:00Z");
        add("payment-kernel", "2025-08-01T12:00:00Z");
        sweeper().sweep(NOW);
        assertThat(repo.countsByClass()).isEqualTo(Map.of("payment", 1L));
    }

    @Test
    void aPaymentRecordPastItsWindowGoes() {
        add("payment-kernel", "2024-01-01T12:00:00Z");
        sweeper().sweep(NOW);
        assertThat(repo.count()).isZero();
    }

    @Test
    void theReportCountsByClass() {
        add("price-sign", "2025-08-01T12:00:00Z", "a");
        add("price-sign", "2025-08-02T12:00:00Z", "b");
        SweepReport report = sweeper().sweep(NOW);
        assertThat(report.byClass()).isEqualTo(Map.of("chatter", 2L));
    }

    @Test
    void theReportReadsAsOneLine() {
        add("price-sign", "2025-08-01T12:00:00Z");
        assertThat(sweeper().sweep(NOW).toString()).contains("deleted 1");
    }

    @Test
    void aHoldStopsADeletion() {
        add("price-sign", "2025-08-01T12:00:00Z");
        new HoldStore(connection).place(hold("case-41", "dispute"));
        SweepReport report = sweeper().sweep(NOW);
        assertThat(report.deleted()).isZero();
        assertThat(report.held()).isEqualTo(1);
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void liftingAHoldLetsTheSweepThrough() {
        add("price-sign", "2025-08-01T12:00:00Z");
        HoldStore holds = new HoldStore(connection);
        holds.place(hold("case-41", "dispute"));
        sweeper().sweep(NOW);
        assertThat(holds.lift("case-41")).isTrue();
        sweeper().sweep(NOW);
        assertThat(repo.count()).isZero();
    }

    @Test
    void liftingAnAbsentHoldReportsIt() {
        assertThat(new HoldStore(connection).lift("case-41")).isFalse();
    }

    @Test
    void aWindowedHoldSavesOnlyItsWindow() {
        add("price-sign", "2025-08-01T12:00:00Z", "inside");
        add("price-sign", "2025-06-01T12:00:00Z", "outside");
        new HoldStore(connection)
                .place(new Hold(
                        "case-41",
                        "dispute",
                        NOW,
                        Optional.of(at("2025-07-15T00:00:00Z")),
                        Optional.of(at("2025-08-15T00:00:00Z"))));
        sweeper().sweep(NOW);
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void aHoldIsReadBack() {
        HoldStore holds = new HoldStore(connection);
        holds.place(
                new Hold("case-41", "chargeback 8821", NOW, Optional.of(at("2025-07-01T00:00:00Z")), Optional.empty()));
        Hold found = holds.get("case-41").orElseThrow();
        assertThat(found.reason()).isEqualTo("chargeback 8821");
        assertThat(found.coversFrom()).contains(at("2025-07-01T00:00:00Z"));
        assertThat(found.coversUntil()).isEmpty();
    }

    @Test
    void placingAHoldTwiceReplacesIt() {
        HoldStore holds = new HoldStore(connection);
        holds.place(hold("case-41", "first"));
        holds.place(hold("case-41", "second"));
        assertThat(holds.all()).hasSize(1);
        assertThat(holds.get("case-41"))
                .hasValueSatisfying(found -> assertThat(found.reason()).isEqualTo("second"));
    }

    @Test
    void anUnknownHoldIsNone() {
        assertThat(new HoldStore(connection).get("case-41")).isEmpty();
    }

    @Test
    void holdsComeBackInTheOrderTheyWerePlaced() {
        HoldStore holds = new HoldStore(connection);
        holds.place(new Hold("second", "b", NOW.shifted(1)));
        holds.place(new Hold("first", "a", NOW));
        assertThat(holds.all()).extracting(Hold::name).containsExactly("first", "second");
    }

    @Test
    void theSweepIsCapped() {
        for (int index = 0; index < 6; index++) {
            add("price-sign", "2025-08-01T12:00:00Z", "line " + index);
        }
        SweepReport report = sweeper(2).sweep(NOW);
        assertThat(report.deleted()).isEqualTo(2);
        assertThat(report.capped()).isTrue();
        assertThat(repo.count()).isEqualTo(4);
    }

    @Test
    void sweepingUntilClearFinishesTheJob() {
        for (int index = 0; index < 6; index++) {
            add("price-sign", "2025-08-01T12:00:00Z", "line " + index);
        }
        SweepReport report = sweeper(2).sweepUntilClear(NOW);
        assertThat(report.deleted()).isEqualTo(6);
        assertThat(repo.count()).isZero();
    }

    @Test
    void sweepingUntilClearStopsWhenNothingMoves() {
        add("price-sign", "2025-08-30T12:00:00Z");
        SweepReport report = sweeper(1).sweepUntilClear(NOW);
        assertThat(report.deleted()).isZero();
    }

    @Test
    void theCandidateQueryIgnoresAnythingTooNew() {
        add("price-sign", "2025-08-30T12:00:00Z");
        assertThat(sweeper().candidates(NOW, 100)).isEmpty();
    }

    @Test
    void theCandidateQueryFindsTheOldOnes() {
        add("price-sign", "2024-01-01T12:00:00Z");
        assertThat(sweeper().candidates(NOW, 100)).hasSize(1);
    }

    @Test
    void theHorizonFollowsTheShortestWindowNotTheLongest() {
        // A chatter record 31 days old is past its 7 day window. A horizon taken
        // from the 180 day payment window would never even look at it.
        add("price-sign", "2025-08-01T12:00:00Z");
        assertThat(sweeper().candidates(NOW, 100)).hasSize(1);
    }
}
