package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.spool.Budget;
import io.github.rkaushikdev.trammelworks.spool.Frames;
import io.github.rkaushikdev.trammelworks.spool.SegmentInfo;
import io.github.rkaushikdev.trammelworks.spool.Segments;
import io.github.rkaushikdev.trammelworks.spool.Spool;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The version promise, checked rather than asserted in a comment: from 1.0.0 a site can be upgraded
 * in place and still read what the previous build left, and these cover the check that says whether
 * that held on a real disk.
 */
class DoctorBuildTest {

    static final NanoTime NOW = NanoTime.parse("2025-06-10T12:00:00Z");

    @TempDir
    Path tmp;

    SiteConfig configFor(UnaryOperator<SiteConfig.Builder> over) {
        return over.apply(SiteConfig.builder("US-TX-0417")
                        .ingest(IngestConfig.at(PosixPath.asPosix(tmp.resolve("containers"))))
                        .spool(SpoolSettings.at(PosixPath.asPosix(tmp.resolve("spool"))))
                        .archive(ArchiveSettings.at(
                                PosixPath.asPosix(tmp.resolve("archive").resolve("records.sqlite3")))))
                .build();
    }

    SiteConfig config() {
        return configFor(UnaryOperator.identity());
    }

    static void fillSpool(SiteConfig config) {
        Spool spool = Spool.builder(
                        config.spool().path(), new Budget(config.spool().budgetBytes()))
                .segmentBytes(4096)
                .compress(false)
                .build();
        for (int index = 0; index < 5; index++) {
            spool.append(new LogRecord(
                    "US-TX-0417", "pump-bridge", Stream.STDOUT, new NanoTime(NOW.epochNs() + index), "line " + index));
        }
        spool.sealAll();
    }

    /** Rewrite the format byte of every segment, as another build would have. */
    static void restamp(SiteConfig config, int value) throws IOException {
        for (SegmentInfo info : Segments.listSegments(config.spool().path())) {
            byte[] raw = Files.readAllBytes(info.path());
            raw[2] = (byte) value;
            Files.write(info.path(), raw);
        }
    }

    static List<String> findings(DoctorReport report, Finding.Level level) {
        return report.findings().stream()
                .filter(item -> item.level() == level)
                .map(Finding::message)
                .toList();
    }

    static void stampSchema(SiteConfig config, String version) {
        try (ArchiveDb connection = Schema.openArchive(config.archive().file())) {
            connection.transaction(() -> {
                connection.execute("INSERT OR REPLACE INTO meta (name, value) VALUES ('schema_version', ?)", version);
            });
        }
    }

    @Test
    void anEmptySpoolHasNoFormats() {
        assertThat(Doctor.spoolFormats(config().spool().path())).isEmpty();
    }

    @Test
    void aSpoolThatIsNotThereHasNoFormats() {
        assertThat(Doctor.spoolFormats(tmp.resolve("absent"))).isEmpty();
    }

    @Test
    void theWrittenSegmentsAreInThisFormat() {
        SiteConfig config = config();
        fillSpool(config);
        assertThat(Doctor.spoolFormats(config.spool().path())).isEqualTo(Set.of(Version.SPOOL_FORMAT));
    }

    @Test
    void aSegmentThatIsNotOneIsIgnored() throws IOException {
        SiteConfig config = config();
        fillSpool(config);
        Files.write(
                config.spool().path().resolve("000000009999-standard.spool"),
                "nothing like a frame".getBytes(StandardCharsets.UTF_8));
        assertThat(Doctor.spoolFormats(config.spool().path())).isEqualTo(Set.of(Version.SPOOL_FORMAT));
    }

    @Test
    void aTruncatedSegmentIsIgnored() throws IOException {
        SiteConfig config = config();
        fillSpool(config);
        Files.write(config.spool().path().resolve("000000009998-standard.spool"), Arrays.copyOf(Frames.MAGIC, 1));
        assertThat(Doctor.spoolFormats(config.spool().path())).isEqualTo(Set.of(Version.SPOOL_FORMAT));
    }

    @Test
    void theBuildIsReported() {
        DoctorReport report = new DoctorReport();
        Doctor.checkBuild(config(), report);
        assertThat(findings(report, Finding.Level.NOTE)).anyMatch(message -> message.contains(Version.VERSION));
    }

    @Test
    void aMatchingSpoolIsNotAProblem() {
        SiteConfig config = config();
        fillSpool(config);
        DoctorReport report = new DoctorReport();
        Doctor.checkBuild(config, report);
        assertThat(findings(report, Finding.Level.FAIL)).isEmpty();
    }

    @Test
    void aSpoolFromANewerBuildFails() throws IOException {
        // The dangerous case. Those segments will not decode, the shipper
        // quarantines them one at a time, and the site looks like it is working
        // while it drops a shift.
        SiteConfig config = config();
        fillSpool(config);
        restamp(config, Version.SPOOL_FORMAT + 1);
        DoctorReport report = new DoctorReport();
        Doctor.checkBuild(config, report);
        assertThat(findings(report, Finding.Level.FAIL)).anyMatch(message -> message.contains("this build writes"));
    }

    @Test
    void aSpoolFromAnOlderBuildIsAllowed() throws IOException {
        // Reading what an older build wrote is the promise, so this must pass.
        SiteConfig config = config();
        fillSpool(config);
        restamp(config, Math.max(Version.SPOOL_FORMAT - 1, 0));
        DoctorReport report = new DoctorReport();
        Doctor.checkBuild(config, report);
        assertThat(findings(report, Finding.Level.FAIL)).isEmpty();
    }

    @Test
    void anArchiveThatHasNeverBeenWrittenIsNotAFault() {
        DoctorReport report = new DoctorReport();
        Doctor.checkBuild(config(), report);
        assertThat(findings(report, Finding.Level.FAIL)).isEmpty();
    }

    @Test
    void aCurrentArchiveIsReportedAsCurrent() {
        SiteConfig config = config();
        Schema.openArchive(config.archive().file()).close();
        DoctorReport report = new DoctorReport();
        Doctor.checkBuild(config, report);
        assertThat(findings(report, Finding.Level.NOTE))
                .anyMatch(message -> message.contains("archive schema " + Schema.SCHEMA_VERSION));
    }

    @Test
    void anArchiveFromANewerBuildFails() {
        SiteConfig config = config();
        stampSchema(config, Integer.toString(Schema.SCHEMA_VERSION + 1));
        DoctorReport report = new DoctorReport();
        Doctor.checkBuild(config, report);
        assertThat(findings(report, Finding.Level.FAIL)).anyMatch(message -> message.contains("this build writes"));
    }

    @Test
    void anArchiveAwaitingAnUpgradeOnlyWarns() {
        SiteConfig config = config();
        stampSchema(config, "1");
        DoctorReport report = new DoctorReport();
        Doctor.checkBuild(config, report);
        assertThat(findings(report, Finding.Level.FAIL)).isEmpty();
        assertThat(findings(report, Finding.Level.WARN)).anyMatch(message -> message.contains("upgrades to"));
    }

    @Test
    void aSiteThatKeepsNoArchiveStillReportsItsBuild() {
        SiteConfig config = configFor(builder ->
                builder.archive(ArchiveSettings.builder().enabled(false).build()));
        DoctorReport report = new DoctorReport();
        Doctor.checkBuild(config, report);
        assertThat(findings(report, Finding.Level.FAIL)).isEmpty();
        assertThat(findings(report, Finding.Level.NOTE)).isNotEmpty();
    }

    @Test
    void theBuildCheckRunsFirst() {
        // An engineer reading a doctor report on a freshly flashed box wants the
        // build line before anything else.
        assertThat(Doctor.runChecks(config()).findings().get(0).check()).isEqualTo("build");
    }

    @Test
    void aConnectionLeftOpenDoesNotStopTheCheck() {
        SiteConfig config = config();
        try (ArchiveDb connection = Schema.openArchive(config.archive().file())) {
            DoctorReport report = new DoctorReport();
            Doctor.checkBuild(config, report);
            assertThat(findings(report, Finding.Level.FAIL)).isEmpty();
            assertThat(connection.isClosed()).isFalse();
        }
    }
}
