package io.github.rkaushikdev.trammelworks.cli;

import static io.github.rkaushikdev.trammelworks.cli.CliFixtures.posix;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.SystemClock;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliHoldTest {

    static final NanoTime NOW = NanoTime.parse("2025-09-01T12:00:00Z");
    static final PriorityTable TABLE = new PriorityTable(Map.of("payment-kernel", "payment", "price-sign", "chatter"));

    @TempDir
    Path tmp;

    Path archiveFile;
    Path config;

    @BeforeEach
    void setUp() {
        archiveFile = tmp.resolve("archive").resolve("records.sqlite3");
        config = CliFixtures.writeConfig(
                tmp,
                "site: US-TX-0417\n"
                        + "spool:\n"
                        + "  directory: " + posix(tmp.resolve("spool")) + "\n"
                        + "archive:\n"
                        + "  path: " + posix(archiveFile) + "\n"
                        + "  retention:\n"
                        + "    chatter: 7d\n");
    }

    void seed(String when) {
        try (ArchiveDb connection = Schema.openArchive(archiveFile)) {
            new Repository(connection, TABLE)
                    .add(
                            new LogRecord(
                                    "US-TX-0417",
                                    "price-sign",
                                    Stream.STDOUT,
                                    NanoTime.parse(when),
                                    "heartbeat at " + when),
                            NOW);
        }
    }

    /**
     * A timestamp inside every retention window, measured from the real clock: the sweep commands
     * read the system clock, so a fixed date would make the test depend on when it ran.
     */
    static String recent() {
        return new SystemClock().now().shifted(-3600L * 1_000_000_000).isoformat();
    }

    long count() {
        try (ArchiveDb connection = Schema.openArchive(archiveFile, true)) {
            return new Repository(connection).count();
        }
    }

    CliRunner.Result invoke(String... args) {
        return CliFixtures.invoke(config, args);
    }

    @Test
    void listingAnEmptyArchiveSaysNoHolds() {
        seed("2025-09-01T00:00:00Z");
        assertThat(invoke("hold", "list").output()).contains("no holds");
    }

    @Test
    void aHoldCanBePlacedAndListed() {
        seed("2025-09-01T00:00:00Z");
        assertThat(invoke("hold", "place", "case-41", "chargeback 8821").exitCode())
                .isEqualTo(Cli.EXIT_OK);
        assertThat(invoke("hold", "list").output()).contains("case-41").contains("chargeback 8821");
    }

    @Test
    void aHoldWithNoWindowCoversEverything() {
        seed("2025-09-01T00:00:00Z");
        invoke("hold", "place", "case-41", "dispute");
        assertThat(invoke("hold", "list").output()).contains("everything");
    }

    @Test
    void aWindowedHoldShowsItsWindow() {
        seed("2025-09-01T00:00:00Z");
        invoke(
                "hold",
                "place",
                "case-41",
                "dispute",
                "--from",
                "2025-07-01T00:00:00Z",
                "--until",
                "2025-08-01T00:00:00Z");
        assertThat(invoke("hold", "list").output())
                .contains("2025-07-01T00:00:00")
                .contains("2025-08-01T00:00:00");
    }

    @Test
    void aHoldCanBeLifted() {
        seed("2025-09-01T00:00:00Z");
        invoke("hold", "place", "case-41", "dispute");
        assertThat(invoke("hold", "lift", "case-41").output()).contains("lifted case-41");
        assertThat(invoke("hold", "list").output()).contains("no holds");
    }

    @Test
    void liftingAnAbsentHoldIsRefused() {
        seed("2025-09-01T00:00:00Z");
        CliRunner.Result result = invoke("hold", "lift", "case-41");
        assertThat(result.exitCode()).isNotEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("no hold called case-41");
    }

    @Test
    void aHoldWithABackwardsWindowIsRefused() {
        seed("2025-09-01T00:00:00Z");
        CliRunner.Result result = invoke(
                "hold",
                "place",
                "case-41",
                "dispute",
                "--from",
                "2025-08-01T00:00:00Z",
                "--until",
                "2025-07-01T00:00:00Z");
        assertThat(result.exitCode()).isNotEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("cannot end before it starts");
    }

    @Test
    void aSweepReportsThePolicy() {
        seed("2025-09-01T00:00:00Z");
        assertThat(invoke("sweep").output()).contains("retention");
    }

    @Test
    void aSweepDeletesAnExpiredRecord() {
        seed("2025-01-01T00:00:00Z");
        assertThat(invoke("sweep").output()).contains("deleted 1").contains("chatter");
    }

    @Test
    void aSweepLeavesAFreshRecord() {
        seed(recent());
        assertThat(invoke("sweep").output()).contains("deleted 0");
    }

    @Test
    void aDryRunIgnoresAFreshRecord() {
        seed(recent());
        assertThat(invoke("sweep", "--dry-run").output()).contains("0 records are old enough");
    }

    @Test
    void aDryRunDeletesNothing() {
        seed("2025-01-01T00:00:00Z");
        assertThat(invoke("sweep", "--dry-run").output()).contains("1 records are old enough");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void aDryRunNamesTheWindowPerClass() {
        seed("2025-01-01T00:00:00Z");
        assertThat(invoke("sweep", "--dry-run").output()).contains("chatter      kept for 7d");
    }

    @Test
    void aHeldRecordSurvivesASweep() {
        seed("2025-01-01T00:00:00Z");
        invoke("hold", "place", "case-41", "dispute");
        invoke("sweep");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void liftingTheHoldThenSweepingRemovesIt() {
        seed("2025-01-01T00:00:00Z");
        invoke("hold", "place", "case-41", "dispute");
        invoke("sweep");
        invoke("hold", "lift", "case-41");
        invoke("sweep");
        assertThat(count()).isZero();
    }

    @Test
    void aSiteWithNoArchiveCannotHold() {
        Path target = CliFixtures.writeConfig(tmp, "site: US-TX-0417\narchive:\n  enabled: false\n");
        assertThat(CliFixtures.invoke(target, "hold", "list").output()).contains("keeps no archive");
    }

    @Test
    void aSiteWithNoArchiveCannotSweep() {
        Path target = CliFixtures.writeConfig(tmp, "site: US-TX-0417\narchive:\n  enabled: false\n");
        assertThat(CliFixtures.invoke(target, "sweep").output()).contains("keeps no archive");
    }
}
