package io.github.rkaushikdev.trammelworks.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.RetentionError;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveSpaceTest {

    static final NanoTime NOW = NanoTime.parse("2025-09-01T12:00:00Z");
    static final long DAY_NS = 24L * 60 * 60 * 1_000_000_000;

    @TempDir
    Path tmp;

    Path path;
    ArchiveDb connection;
    Repository repo;

    @BeforeEach
    void setUp() {
        path = ArchiveFixtures.archivePath(tmp);
        connection = Schema.openArchive(path);
        repo = new Repository(connection, ArchiveFixtures.TABLE);
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    void fill(String unit, int count) {
        fill(unit, count, 0);
    }

    void fill(String unit, int count, int day) {
        List<LogRecord> batch = IntStream.range(0, count)
                .mapToObj(index -> ArchiveFixtures.record(
                        unit,
                        new NanoTime(NOW.epochNs() - (day * DAY_NS) + index),
                        unit + " " + day + " " + index + " " + "x".repeat(300)))
                .toList();
        repo.addAll(batch, NOW);
    }

    Trimmer trimmer(long cap) {
        return trimmer(cap, 200);
    }

    Trimmer trimmer(long cap, int batch) {
        return new Trimmer(connection, path, cap, ArchiveFixtures.TABLE, batch);
    }

    @Test
    void anEmptyFootprintIsAllZero() {
        Footprint found = new Footprint();
        assertThat(found.total()).isZero();
        assertThat(found.used()).isZero();
        assertThat(found.freeFraction()).isEqualTo(0.0);
    }

    @Test
    void aFootprintCountsTheWalAsWell() {
        assertThat(new Footprint(1000, 250, 100, 10).total()).isEqualTo(1250);
    }

    @Test
    void theUsedSizeLeavesOutTheFreePages() {
        assertThat(new Footprint(1000, 0, 100, 10, 4).used()).isEqualTo(600);
    }

    @Test
    void theUsedSizeNeverGoesNegative() {
        assertThat(new Footprint(1000, 0, 100, 2, 9).used()).isZero();
    }

    @Test
    void theUsedSizeLeavesTheLogOut() {
        // Only a checkpoint returns the log, so a trim measured against it would
        // never finish.
        assertThat(new Footprint(1000, 9000, 100, 10).used()).isEqualTo(1000);
    }

    @Test
    void aCheckpointFoldsTheLogBackIn() {
        fill("price-sign", 500);
        Space.checkpoint(connection);
        assertThat(Space.measure(connection, path).walBytes()).isZero();
    }

    @Test
    void aCheckpointOnAClosedArchiveIsRefused() {
        connection.close();
        assertThatThrownBy(() -> Space.checkpoint(connection))
                .isInstanceOf(RetentionError.class)
                .hasMessageContaining("will not checkpoint");
    }

    @Test
    void whatAVacuumWouldGiveBackIsTheFreePages() {
        Footprint found = new Footprint(1000, 0, 100, 10, 4);
        assertThat(found.reclaimable()).isEqualTo(400);
        assertThat(found.freeFraction()).isCloseTo(0.4, within(1e-6));
    }

    @Test
    void aFootprintReadsAsOneLine() {
        assertThat(new Footprint(1000, 0, 100, 10, 4)).hasToString("1000 bytes, 400 reclaimable (4 of 10 pages free)");
    }

    @Test
    void aMissingFileMeasuresAsNothing() {
        assertThat(Space.fileSize(tmp.resolve("absent"))).isZero();
    }

    @Test
    void measuringARealArchiveReportsPages() {
        fill("price-sign", 200);
        Footprint found = Space.measure(connection, path);
        assertThat(found.pageSize()).isPositive();
        assertThat(found.pageCount()).isPositive();
        assertThat(found.total()).isPositive();
    }

    @Test
    void measuringAClosedArchiveIsRefused() {
        connection.close();
        assertThatThrownBy(() -> Space.measure(connection, path))
                .isInstanceOf(RetentionError.class)
                .hasMessageContaining("will not report its size");
    }

    @Test
    void nothingFreeIsNotWorthVacuuming() {
        assertThat(Space.worthVacuuming(new Footprint(1000, 0, 100, 10))).isFalse();
    }

    @Test
    void aBigFreePortionIsWorthVacuuming() {
        Footprint found = new Footprint(1000, 0, 100, 10, 5);
        assertThat(Space.worthVacuuming(found, 10_000_000, Space.DEFAULT_MIN_FRACTION))
                .isTrue();
    }

    @Test
    void aSmallFreePortionIsNot() {
        Footprint found = new Footprint(100_000, 0, 100, 1000, 1);
        assertThat(Space.worthVacuuming(found, 10_000_000, Space.DEFAULT_MIN_FRACTION))
                .isFalse();
    }

    @Test
    void theDefaultFractionIsTheOneDocumented() {
        assertThat(new Footprint(1000, 0, 100, 10, 2).freeFraction())
                .isCloseTo(Space.DEFAULT_MIN_FRACTION, within(1e-6));
    }

    @Test
    void aVacuumGivesTheFileBack() {
        fill("price-sign", 2000);
        connection.transaction(() -> {
            connection.execute("DELETE FROM records");
        });
        // The records were written in a few real transactions, so the log never reached the
        // automatic checkpoint and the main file still holds only the schema. Fold the log in
        // first, so the file holds the deleted pages the vacuum is meant to give back.
        Space.checkpoint(connection);
        long before = Space.fileSize(path);
        long freed = Space.vacuum(connection, path);
        assertThat(freed).isPositive();
        assertThat(Space.fileSize(path)).isLessThan(before);
    }

    @Test
    void aVacuumOnAFullArchiveGivesLittleBack() {
        fill("price-sign", 500);
        assertThat(Space.vacuum(connection, path)).isLessThan(Space.fileSize(path));
    }

    @Test
    void aVacuumKeepsTheRecords() {
        fill("price-sign", 300);
        Space.vacuum(connection, path);
        assertThat(repo.count()).isEqualTo(300);
    }

    @Test
    void aVacuumOnAClosedArchiveIsRefused() {
        connection.close();
        assertThatThrownBy(() -> Space.vacuum(connection, path))
                .isInstanceOf(RetentionError.class)
                .hasMessageContaining("vacuum failed");
    }

    @Test
    void aDiskWithRoomAllowsTheVacuum() {
        assertThat(Space.roomToVacuum(tmp.resolve("records.sqlite3"), 1024)).isTrue();
    }

    @Test
    void aDiskThatCannotHoldTheCopyRefuses() {
        assertThat(Space.roomToVacuum(tmp.resolve("records.sqlite3"), 1L << 62)).isFalse();
    }

    @Test
    void aCapOfNothingIsRefused() {
        assertThatThrownBy(() -> trimmer(0))
                .isInstanceOf(RetentionError.class)
                .hasMessageContaining("cap must be positive");
    }

    @Test
    void aBatchOfNothingIsRefused() {
        assertThatThrownBy(() -> trimmer(1024, 0))
                .isInstanceOf(RetentionError.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void anArchiveUnderItsCapIsLeftAlone() {
        fill("price-sign", 50);
        TrimReport report = trimmer(100L * 1024 * 1024).run(List.of(), NOW);
        assertThat(report.idle()).isTrue();
        assertThat(report.stopped()).isEqualTo("already under the cap");
    }

    @Test
    void anArchiveOverItsCapIsTrimmed() {
        fill("price-sign", 2000);
        Space.checkpoint(connection);
        long before = repo.count();
        TrimReport report = trimmer(100_000).run(List.of(), NOW);
        assertThat(report.deleted()).isPositive();
        assertThat(repo.count()).isLessThan(before);
    }

    @Test
    void trimmingStopsOnceItIsUnderTheCap() {
        fill("price-sign", 2000);
        Space.checkpoint(connection);
        Trimmer built = trimmer(200_000);
        built.run(List.of(), NOW);
        assertThat(built.overBy()).isZero();
        assertThat(repo.count()).isPositive();
    }

    @Test
    void theLeastImportantClassGoesFirst() {
        fill("price-sign", 1200);
        fill("pump-bridge", 1200);
        Space.checkpoint(connection);
        long cap = Space.measure(connection, path).used() - 100_000;
        TrimReport report = trimmer(cap).run(List.of(), NOW);
        assertThat(report.byClass().getOrDefault("chatter", 0L)).isPositive();
        assertThat(report.byClass()).doesNotContainKey("standard");
    }

    @Test
    void aProtectedClassIsNeverTrimmed() {
        fill("payment-kernel", 3000);
        Space.checkpoint(connection);
        TrimReport report = trimmer(100_000).run(List.of(), NOW);
        assertThat(ArchiveFixtures.counts(connection)).containsEntry("payment", 3000L);
        assertThat(report.stopped()).isEqualTo("nothing left that may be deleted");
    }

    @Test
    void theOldestRecordsGoFirst() {
        fill("price-sign", 1000, 30);
        fill("price-sign", 1000, 1);
        Space.checkpoint(connection);
        trimmer(250_000).run(List.of(), NOW);
        Row row = connection
                .queryOne("SELECT MIN(observed_ns) AS oldest FROM records")
                .orElseThrow();
        assertThat(row.getLong("oldest")).isGreaterThan(NOW.epochNs() - (2 * DAY_NS));
    }

    @Test
    void aHeldRecordIsNotTrimmed() {
        fill("price-sign", 2000, 30);
        Space.checkpoint(connection);
        Hold hold = new Hold(
                "dispute-88213",
                "chargeback",
                NOW,
                Optional.of(new NanoTime(NOW.epochNs() - (60 * DAY_NS))),
                Optional.of(NOW));
        TrimReport report = trimmer(100_000).run(List.of(hold), NOW);
        assertThat(report.deleted()).isZero();
        assertThat(report.held()).isPositive();
    }

    @Test
    void aHoldThatCoversNothingDoesNotStopTheTrim() {
        fill("price-sign", 2000, 1);
        Space.checkpoint(connection);
        Hold hold = new Hold(
                "dispute-11",
                "chargeback",
                NOW,
                Optional.of(new NanoTime(NOW.epochNs() - (400 * DAY_NS))),
                Optional.of(new NanoTime(NOW.epochNs() - (300 * DAY_NS))));
        assertThat(trimmer(100_000).run(List.of(hold), NOW).deleted()).isPositive();
    }

    @Test
    void aTrimReportReadsAsOneLine() {
        assertThat(new TrimReport(10, 8, 2, "")).hasToString("trimmed 8 of 10 examined, 2 held");
    }

    @Test
    void aTrimReportSaysWhyItStopped() {
        assertThat(new TrimReport(0, 0, 0, "nothing left that may be deleted").toString())
                .contains("stopped: nothing left");
    }

    @Test
    void theTrimOrderLeavesProtectedClassesOut() {
        assertThat(trimmer(1024).order()).doesNotContain("payment");
    }

    @Test
    void theTrimOrderStartsWithTheCheapest() {
        assertThat(trimmer(1024).order().get(0)).isEqualTo("chatter");
    }

    @Test
    void theNextClassUpIsOnlyTouchedOnceTheFirstIsGone() {
        fill("price-sign", 1200);
        fill("pump-bridge", 1200);
        Space.checkpoint(connection);
        TrimReport report = trimmer(100_000).run(List.of(), NOW);
        assertThat(report.byClass().get("standard")).isPositive();
        assertThat(report.byClass()).containsEntry("chatter", 1200L);
    }

    @Test
    void aVacuumAfterATrimHandsTheCardTheSpace() {
        fill("price-sign", 3000);
        Space.checkpoint(connection);
        long before = Space.fileSize(path);
        trimmer(200_000).run(List.of(), NOW);
        assertThat(Space.fileSize(path)).isEqualTo(before);
        Space.vacuum(connection, path);
        assertThat(Space.fileSize(path)).isLessThan(before);
    }
}
