package io.github.rkaushikdev.trammelworks.cli;

import static io.github.rkaushikdev.trammelworks.cli.CliFixtures.posix;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliQueryTest {

    static final NanoTime NOW = NanoTime.parse("2025-03-04T12:00:00Z");

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
                        + "  path: " + posix(archiveFile) + "\n");
    }

    void add(LogRecord... records) {
        try (ArchiveDb connection = Schema.openArchive(archiveFile)) {
            Repository repo = new Repository(connection);
            for (LogRecord record : records) {
                repo.add(record, NOW);
            }
        }
    }

    static LogRecord line(String unit, String when, String message, Map<String, String> labels) {
        return new LogRecord("US-TX-0417", unit, Stream.STDOUT, NanoTime.parse("2025-03-04T" + when + "Z"), message)
                .replaceLabels(labels);
    }

    void fill() {
        add(
                line("payment-kernel", "11:00:00", "auth approved", Map.of("lane", "3")),
                line("payment-kernel", "11:15:00", "auth declined", Map.of("lane", "4")),
                line("pump-bridge", "11:30:00", "nozzle 1 lifted", Map.of()));
    }

    static LogRecord at(String message) {
        return new LogRecord("US-TX-0417", "pump-bridge", Stream.STDOUT, NOW, message);
    }

    CliRunner.Result invoke(String... args) {
        String[] full = new String[args.length + 1];
        full[0] = "query";
        System.arraycopy(args, 0, full, 1, args.length);
        return CliFixtures.invoke(config, full);
    }

    /** The record lines, without the notes the command writes to standard error. */
    static List<String> recordsPrinted(String output) {
        return output.strip()
                .lines()
                .filter(line -> !line.isEmpty())
                .filter(line ->
                        !line.startsWith("next page:") && !line.startsWith("stopped after") && !line.startsWith("sql:"))
                .toList();
    }

    @Test
    void anEmptyFilterListsEverything() {
        fill();
        CliRunner.Result result = invoke("");
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
        assertThat(result.output().strip().lines()).hasSize(3);
    }

    @Test
    void aRecordLineCarriesTheTimeAndUnit() {
        fill();
        assertThat(invoke("unit = pump-bridge").output())
                .contains("pump-bridge stdout nozzle 1 lifted")
                .contains("2025-03-04T11:30:00");
    }

    @Test
    void aFilterNarrows() {
        fill();
        CliRunner.Result result = invoke("message ~ declined");
        assertThat(result.output().strip()).doesNotContain("\n").contains("auth declined");
    }

    @Test
    void aLabelFilterWorksFromTheCommandLine() {
        fill();
        assertThat(invoke("label.lane = 3").output()).contains("auth approved").doesNotContain("auth declined");
    }

    @Test
    void countingPrintsANumber() {
        fill();
        assertThat(invoke("--count", "unit = payment-kernel").output().strip()).isEqualTo("2");
    }

    @Test
    void countingAnEmptyResultIsZero() {
        fill();
        assertThat(invoke("--count", "unit = absent").output().strip()).isEqualTo("0");
    }

    @Test
    void aLimitIsHonoured() {
        fill();
        assertThat(recordsPrinted(invoke("limit 1").output())).hasSize(1);
    }

    @Test
    void aFullPageOffersTheNextOne() {
        fill();
        assertThat(invoke("limit 1").output()).contains("next page: --after ");
    }

    @Test
    void theCursorItPrintsCanBeHandedBack() {
        fill();
        String first = invoke("limit 1").output();
        String cursor = first.split("next page: --after ")[1].split("\\s+")[0];
        String second = invoke("limit 1", "--after", cursor).output();
        assertThat(recordsPrinted(second)).isNotEmpty().isNotEqualTo(recordsPrinted(first));
    }

    @Test
    void aLastPageOffersNothingMore() {
        fill();
        assertThat(invoke("limit 10000").output()).doesNotContain("next page:");
    }

    @Test
    void aMalformedCursorIsRefused() {
        fill();
        CliRunner.Result result = invoke("--after", "nonsense");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("cursor is malformed");
    }

    @Test
    void explainShowsThePlan() {
        fill();
        assertThat(invoke("--explain", "unit = a").output()).contains("sql: unit = ?");
    }

    @Test
    void explainNamesAPostFilter() {
        fill();
        assertThat(invoke("--explain", "label.lane = 3").output()).contains("in memory");
    }

    @Test
    void aLongMessageIsTruncatedByDefault() {
        add(at("z".repeat(300)));
        assertThat(invoke("").output()).doesNotContain("z".repeat(200));
    }

    @Test
    void fullPrintsTheWholeMessage() {
        add(at("z".repeat(300)));
        assertThat(invoke("--full", "").output()).contains("z".repeat(300));
    }

    @Test
    void aLowConfidenceRecordIsMarked() {
        add(at("uncertain").withClockConfidence("extrapolated"));
        assertThat(invoke("").output()).contains("Z? pump-bridge");
    }

    @Test
    void theCeilingTruncatesAndWarns() {
        fill();
        assertThat(invoke("--ceiling", "1", "").output()).contains("narrow the filter");
    }

    @Test
    void aBrokenFilterIsRefused() {
        fill();
        CliRunner.Result result = invoke("unit ==== a");
        assertThat(result.exitCode()).isNotEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("stanchion.query");
    }

    @Test
    void anUnknownFieldIsRefused() {
        fill();
        assertThat(invoke("unti = a").output()).contains("unknown field");
    }

    @Test
    void aMissingArchiveIsReported() {
        CliRunner.Result result = invoke("");
        assertThat(result.exitCode()).isNotEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("does not exist");
    }

    @Test
    void aSiteWithNoArchiveSaysSo() {
        Path target = CliFixtures.writeConfig(tmp, "site: US-TX-0417\narchive:\n  enabled: false\n");
        assertThat(CliFixtures.invoke(target, "query", "").output()).contains("keeps no archive");
    }

    @Test
    void aQueryRunsWhileTheCollectorIsWriting() {
        // The collector holds a write connection to the same file all day. A query
        // that took a write lock would stop it, so this holds one open and demands
        // an answer anyway.
        fill();
        try (ArchiveDb writer = Schema.openArchive(archiveFile)) {
            writer.execute("BEGIN IMMEDIATE");
            try {
                CliRunner.Result result = invoke("");
                assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
                assertThat(result.output().strip().lines()).hasSize(3);
            } finally {
                writer.execute("ROLLBACK");
            }
        }
    }

    @Test
    void verboseReportsWhatWasScanned() {
        fill();
        assertThat(CliRunner.invoke("--config", config.toString(), "-v", "query", "")
                        .output())
                .contains("records from");
    }
}
