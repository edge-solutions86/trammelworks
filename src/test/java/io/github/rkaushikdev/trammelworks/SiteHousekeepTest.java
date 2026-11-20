package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.archive.Hold;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.RetentionPolicy;
import io.github.rkaushikdev.trammelworks.archive.Space;
import io.github.rkaushikdev.trammelworks.archive.SweepReport;
import io.github.rkaushikdev.trammelworks.archive.TrimReport;
import io.github.rkaushikdev.trammelworks.ship.MemoryTransport;
import io.github.rkaushikdev.trammelworks.ship.ShipSettings;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteHousekeepTest {

    static final NanoTime NOW = NanoTime.parse("2025-09-01T12:00:00Z");
    static final long DAY_NS = 24L * 60 * 60 * 1_000_000_000;
    static final PriorityTable TABLE = new PriorityTable(Map.of("payment-kernel", "payment", "price-sign", "chatter"));
    static final RetentionPolicy POLICY = new RetentionPolicy(
            Map.of("payment", Duration.ofDays(180), "standard", Duration.ofDays(30), "chatter", Duration.ofDays(7)));

    @TempDir
    Path tmp;

    SiteTree tree;
    final List<Site> built = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tree = new SiteTree(tmp.resolve("log"));
    }

    @AfterEach
    void tearDown() {
        built.forEach(Site::release);
    }

    Path archivePath() {
        return tmp.resolve("archive").resolve("records.sqlite3");
    }

    Site build() {
        return build(UnaryOperator.identity());
    }

    Site build(UnaryOperator<ArchiveSettings.Builder> over) {
        ManualClock clock = new ManualClock();
        clock.setWall("2025-09-01T12:00:00Z");
        ArchiveSettings archive = over.apply(ArchiveSettings.builder()
                        .path(PosixPath.asPosix(archivePath()))
                        .retention(POLICY)
                        .maxBytes(200_000))
                .build();
        SiteConfig config = SiteConfig.builder("US-TX-0417")
                .ingest(IngestConfig.at(PosixPath.asPosix(tree.root())))
                .spool(SpoolSettings.builder()
                        .directory(PosixPath.asPosix(tmp.resolve("spool")))
                        .budgetBytes(8_000_000)
                        .segmentBytes(200_000)
                        .priority(TABLE)
                        .build())
                .ship(ShipSettings.builder().monthlyAllowanceBytes(50_000_000).build())
                .archive(archive)
                .build();
        Site site = Site.build(config, new MemoryTransport(), clock, null, null);
        built.add(site);
        site.open();
        return site;
    }

    static void load(Site site, String unit, int count, int day) {
        site.absorb(IntStream.range(0, count)
                .mapToObj(index -> new LogRecord(
                        "US-TX-0417",
                        unit,
                        Stream.STDOUT,
                        new NanoTime(NOW.epochNs() - (day * DAY_NS) + index),
                        unit + " " + day + " " + index + " " + "x".repeat(300)))
                .toList());
    }

    static Repository archiveOf(Site site) {
        return site.archive().orElseThrow();
    }

    static void settle(Site site) {
        Space.checkpoint(archiveOf(site).db());
    }

    @Test
    void aSiteWithNoArchiveHousekeepsToNothing() {
        Site site = build(archive -> archive.enabled(false));
        assertThat(site.housekeep().idle()).isTrue();
        site.close();
    }

    @Test
    void aQuietArchiveIsLeftAlone() {
        Site site = build();
        load(site, "pump-bridge", 20, 1);
        settle(site);
        assertThat(site.housekeep().idle()).isTrue();
        site.close();
    }

    @Test
    void theSweepTakesWhatRetentionAllows() {
        Site site = build();
        load(site, "price-sign", 200, 40);
        settle(site);
        HousekeepReport report = site.housekeep();
        assertThat(report.sweep().deleted()).isEqualTo(200);
        assertThat(report.trim().deleted()).isZero();
        site.close();
    }

    @Test
    void nothingIsTrimmedUnlessTheConfigSaysSo() {
        Site site = build(archive -> archive.maxBytes(50_000));
        load(site, "price-sign", 1500, 1);
        settle(site);
        HousekeepReport report = site.housekeep();
        assertThat(report.trim().deleted()).isZero();
        assertThat(archiveOf(site).count()).isEqualTo(1500);
        site.close();
    }

    @Test
    void theCapTrimsWhenItIsTurnedOn() {
        Site site = build(archive -> archive.maxBytes(100_000).trimToCap(true));
        load(site, "price-sign", 1500, 1);
        settle(site);
        assertThat(site.housekeep().trim().deleted()).isPositive();
        site.close();
    }

    @Test
    void theTrimLeavesThePaymentRecords() {
        Site site = build(archive -> archive.maxBytes(60_000).trimToCap(true));
        load(site, "payment-kernel", 800, 1);
        load(site, "price-sign", 800, 1);
        settle(site);
        site.housekeep();
        assertThat(archiveOf(site).countsByClass()).containsEntry("payment", 800L);
        site.close();
    }

    @Test
    void aHoldSurvivesTheTrim() {
        Site site = build(archive -> archive.maxBytes(60_000).trimToCap(true));
        load(site, "price-sign", 1200, 1);
        settle(site);
        site.sweeper()
                .orElseThrow()
                .holds()
                .place(new Hold(
                        "dispute-4412",
                        "chargeback",
                        NOW,
                        Optional.of(new NanoTime(NOW.epochNs() - (10 * DAY_NS))),
                        Optional.of(NOW)));
        HousekeepReport report = site.housekeep();
        assertThat(report.trim().deleted()).isZero();
        assertThat(report.trim().held()).isPositive();
        site.close();
    }

    @Test
    void theVacuumGivesTheCardItsSpaceBack() {
        Site site = build(archive -> archive.vacuumMinReclaimBytes(1));
        load(site, "price-sign", 3000, 40);
        settle(site);
        long before = Space.fileSize(archivePath());
        HousekeepReport report = site.housekeep();
        assertThat(report.reclaimed()).isPositive();
        assertThat(Space.fileSize(archivePath())).isLessThan(before);
        site.close();
    }

    @Test
    void theVacuumCanBeTurnedOff() {
        Site site = build(archive -> archive.vacuum(false));
        load(site, "price-sign", 3000, 40);
        settle(site);
        long before = Space.fileSize(archivePath());
        HousekeepReport report = site.housekeep();
        assertThat(report.reclaimed()).isZero();
        assertThat(Space.fileSize(archivePath())).isEqualTo(before);
        site.close();
    }

    @Test
    void aSmallSavingIsNotWorthTheWriteCycles() {
        // Most of the file stays, so neither the byte floor nor the fraction says
        // rewrite it. Either one on its own would be enough, which is why the kept
        // records have to outweigh the swept ones for this to mean anything.
        Site site = build(archive -> archive.vacuumMinReclaimBytes(1024L * 1024 * 1024));
        load(site, "price-sign", 3000, 1);
        load(site, "price-sign", 20, 40);
        settle(site);
        HousekeepReport report = site.housekeep();
        assertThat(report.sweep().deleted()).isEqualTo(20);
        assertThat(report.reclaimed()).isZero();
        site.close();
    }

    @Test
    void theReportMeasuresBothSides() {
        Site site = build(archive -> archive.vacuumMinReclaimBytes(1));
        load(site, "price-sign", 2000, 40);
        settle(site);
        HousekeepReport report = site.housekeep();
        assertThat(report.before().total()).isGreaterThan(report.after().total());
        site.close();
    }

    @Test
    void theReportReadsAsOneLine() {
        assertThat(new HousekeepReport()).hasToString("swept 0, trimmed 0, freed 0 bytes");
    }

    @Test
    void theCountersCarryTheArchiveSize() {
        Site site = build();
        load(site, "price-sign", 100, 1);
        settle(site);
        Map<String, Long> found = site.snapshot();
        assertThat(found.get("archive_bytes")).isPositive();
        assertThat(found).containsEntry("archive_limit", 200_000L);
        assertThat(found.get("archive_used")).isLessThanOrEqualTo(found.get("archive_bytes"));
        site.close();
    }

    @Test
    void aTrimIsCounted() {
        Site site = build(archive -> archive.maxBytes(100_000).trimToCap(true));
        load(site, "price-sign", 1500, 1);
        settle(site);
        site.housekeep();
        assertThat(site.snapshot().get("archive_trimmed")).isPositive();
        site.close();
    }

    @Test
    void aVacuumIsCounted() {
        Site site = build(archive -> archive.vacuumMinReclaimBytes(1));
        load(site, "price-sign", 2000, 40);
        settle(site);
        site.housekeep();
        assertThat(site.snapshot()).containsEntry("archive_vacuumed", 1L);
        site.close();
    }

    @Test
    void aNegativeVacuumFloorIsRefused() {
        assertThatThrownBy(() ->
                        ArchiveSettings.builder().vacuumMinReclaimBytes(-1).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void theNewKeysComeOutOfTheConfigBlock() {
        ArchiveSettings settings = ArchiveSettings.archiveSettingsFromMapping(
                Maps.of("trim_to_cap", true, "vacuum", false, "vacuum_min_reclaim", "8MiB"));
        assertThat(settings.trimToCap()).isTrue();
        assertThat(settings.vacuum()).isFalse();
        assertThat(settings.vacuumMinReclaimBytes()).isEqualTo(8L * 1024 * 1024);
    }

    @Test
    void anUnknownArchiveKeyIsStillRefused() {
        assertThatThrownBy(() -> ArchiveSettings.archiveSettingsFromMapping(Map.of("vacuum_when", "sunday")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown archive key");
    }

    @Test
    void theDescriptionSaysWhatTheCapIs() {
        assertThat(new ArchiveSettings().describe()).contains("cap 2GiB");
    }

    @Test
    void theDescriptionSaysWhenItTrims() {
        assertThat(ArchiveSettings.builder().trimToCap(true).build().describe()).contains("trims to fit");
    }

    @Test
    void aDisabledArchiveDescribesItselfAsOff() {
        assertThat(ArchiveSettings.builder().enabled(false).build().describe()).isEqualTo("archive off");
    }

    @Test
    void housekeepingAfterAVacuumStillMeasures() {
        Site site = build(archive -> archive.vacuumMinReclaimBytes(1));
        load(site, "price-sign", 2000, 40);
        settle(site);
        site.housekeep();
        assertThat(Space.measure(archiveOf(site).db(), archivePath()).used()).isPositive();
        site.close();
    }

    @Test
    void aSiteWithAnArchiveHasAHousekeeper() {
        Site site = build();
        assertThat(site.housekeeper()).isPresent();
        site.close();
    }

    @Test
    void aSiteWithoutOneHasNone() {
        Site site = build(archive -> archive.enabled(false));
        assertThat(site.housekeeper()).isEmpty();
        site.close();
    }

    @Test
    void theHousekeeperCanBeDrivenOnItsOwn() {
        Site site = build(archive -> archive.vacuumMinReclaimBytes(1));
        load(site, "price-sign", 2000, 40);
        settle(site);
        Housekeeper keeper = site.housekeeper().orElseThrow();
        assertThat(keeper.sweep().deleted()).isEqualTo(2000);
        assertThat(keeper.vacuum()).isPositive();
        site.close();
    }

    @Test
    void theReportTotalsBothKindsOfDeletion() {
        HousekeepReport report = new HousekeepReport(new SweepReport(4, 4, 0, false), new TrimReport(3, 3, 0, ""), 0);
        assertThat(report.deleted()).isEqualTo(7);
    }

    @Test
    void theHousekeeperMeasuresTheSameFile() {
        Site site = build();
        Housekeeper keeper = site.housekeeper().orElseThrow();
        assertThat(keeper.path()).isEqualTo(archivePath());
        assertThat(keeper.footprint().total()).isPositive();
        site.close();
    }
}
