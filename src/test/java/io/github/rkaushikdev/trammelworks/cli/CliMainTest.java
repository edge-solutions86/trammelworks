package io.github.rkaushikdev.trammelworks.cli;

import static io.github.rkaushikdev.trammelworks.cli.CliRunner.invoke;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.trammelworks.Build;
import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.PosixPath;
import io.github.rkaushikdev.trammelworks.QueryError;
import io.github.rkaushikdev.trammelworks.ShipError;
import io.github.rkaushikdev.trammelworks.Version;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.archive.SqlFailure;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliMainTest {

    static final String SITE_YAML =
            """

            site: US-TX-0417
            spool:
              budget: 1GiB
              segment: 16MiB
            """;

    @TempDir
    Path tmp;

    Path config() throws IOException {
        Path target = tmp.resolve("site.yaml");
        Files.writeString(target, SITE_YAML, StandardCharsets.UTF_8);
        return target;
    }

    final StringWriter errors = new StringWriter();

    CliContext context(Path configPath) {
        return new CliContext(
                configPath,
                false,
                new PrintWriter(new StringWriter()),
                new PrintWriter(errors),
                new ByteArrayInputStream(new byte[0]));
    }

    String err() {
        return errors.toString();
    }

    Path archiveConfig(boolean enabled) throws IOException {
        Path target = tmp.resolve("site.yaml");
        String body =
                "site: US-TX-0417\nspool:\n  directory: " + PosixPath.asPosix(tmp.resolve("spool")) + "\narchive:\n";
        body += enabled
                ? "  path: " + PosixPath.asPosix(tmp.resolve("archive").resolve("records.sqlite3")) + "\n"
                : "  enabled: false\n";
        Files.writeString(target, body, StandardCharsets.UTF_8);
        return target;
    }

    /**
     * A config whose archive file has actually been created. Opening read only refuses a file that
     * is not there: a reader that created an empty archive would report a site with no records
     * rather than a site that has never written one.
     */
    Path made() throws IOException {
        Path target = archiveConfig(true);
        Schema.openArchive(tmp.resolve("archive").resolve("records.sqlite3")).close();
        return target;
    }

    @Test
    void helpListsTheCommands() {
        CliRunner.Result result = invoke("--help");
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("config").contains("version");
    }

    @Test
    void shortHelpFlagWorks() {
        assertThat(invoke("-h").exitCode()).isEqualTo(Cli.EXIT_OK);
    }

    @Test
    void versionCommandPrintsTheVersion() {
        assertThat(invoke("version").output().strip()).isEqualTo(Version.VERSION);
    }

    @Test
    void versionOptionPrintsTheProgramName() {
        CliRunner.Result result = invoke("--version");
        assertThat(result.output()).contains("stanchion").contains(Version.VERSION);
    }

    @Test
    void configPrintsWhatItUnderstood() throws IOException {
        CliRunner.Result result = invoke("--config", config().toString(), "config");
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("site: US-TX-0417").contains("budget: 1073741824");
    }

    @Test
    void configCheckIsQuiet() throws IOException {
        assertThat(invoke("--config", config().toString(), "config", "--check")
                        .output()
                        .strip())
                .isEqualTo("ok US-TX-0417");
    }

    @Test
    void aMissingConfigExitsTwo() {
        CliRunner.Result result = invoke("--config", tmp.resolve("nope.yaml").toString(), "config");
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_CONFIG);
        assertThat(result.output()).contains("stanchion.config");
    }

    @Test
    void aBrokenConfigNamesTheCode() throws IOException {
        Path target = tmp.resolve("site.yaml");
        Files.writeString(target, "site: [oops", StandardCharsets.UTF_8);
        CliRunner.Result result = invoke("--config", target.toString(), "config");
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_CONFIG);
        assertThat(result.output()).contains("not valid yaml");
    }

    @Test
    void aConfigFailurePrintsNoTraceback() {
        CliRunner.Result result = invoke("--config", tmp.resolve("nope.yaml").toString(), "config");
        assertThat(result.output()).doesNotContain("Exception").doesNotContain("\tat ");
    }

    @Test
    void anUnknownCommandIsRefused() {
        assertThat(invoke("polish-the-pumps").exitCode()).isNotEqualTo(Cli.EXIT_OK);
    }

    @Test
    void theDefaultConfigPathIsTheEtcOne() {
        assertThat(invoke("--help").output()).contains("/etc/stanchion/site.yaml");
    }

    @Test
    void runReturnsZeroForAGoodCommand() {
        assertThat(invoke("version").exitCode()).isEqualTo(Cli.EXIT_OK);
    }

    @Test
    void runReturnsTwoForAMissingConfig() {
        assertThat(invoke("--config", tmp.resolve("nope.yaml").toString(), "config")
                        .exitCode())
                .isEqualTo(Cli.EXIT_CONFIG);
    }

    @Test
    void runReturnsACodeForABadOption() {
        assertThat(invoke("--nonsense").exitCode()).isNotEqualTo(Cli.EXIT_OK);
    }

    @Test
    void verboseIsAcceptedOnTheGroup() throws IOException {
        assertThat(invoke("--config", config().toString(), "-v", "config", "--check")
                        .exitCode())
                .isEqualTo(Cli.EXIT_OK);
    }

    @Test
    void aGuardLetsACleanRunThrough() {
        List<String> seen = new java.util.ArrayList<>();
        context(tmp).reporting(() -> {
            seen.add("ran");
        });
        assertThat(seen).containsExactly("ran");
    }

    @Test
    void aGuardTurnsAnErrorIntoOneLine() {
        CliExit caught = catchThrowableOfType(CliExit.class, () -> context(tmp).reporting(() -> {
            throw new ConfigError("no such file", "/etc/stanchion/site.yaml");
        }));
        assertThat(caught.code()).isEqualTo(Cli.EXIT_FAILED);
        assertThat(err()).contains("stanchion.config: no such file");
    }

    @Test
    void aGuardCarriesTheExitCodeItWasGiven() {
        CliExit caught = catchThrowableOfType(CliExit.class, () -> context(tmp).reporting(Cli.EXIT_CONFIG, () -> {
            throw new ConfigError("bad");
        }));
        assertThat(caught.code()).isEqualTo(Cli.EXIT_CONFIG);
    }

    @Test
    void aGuardDoesNotSwallowAnythingElse() {
        assertThatThrownBy(() -> context(tmp).reporting(() -> {
                    throw new IllegalArgumentException("not ours");
                }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyMessageCarriesTheErrorCode() {
        // The whole reason for one guard: a support call says paste me what it
        // says, and what it says has to name the code whichever command ran.
        CliContext context = context(tmp);
        for (RuntimeException error : List.of(new ConfigError("a"), new QueryError("b"), new ShipError("c"))) {
            assertThatThrownBy(() -> context.reporting(() -> {
                        throw error;
                    }))
                    .isInstanceOf(CliExit.class);
        }
        assertThat(err().lines().map(line -> line.split(":")[0]).toList())
                .containsExactly("stanchion.config", "stanchion.query", "stanchion.ship");
    }

    @Test
    void loadingAGoodConfigGivesItBack() throws IOException {
        assertThat(context(config()).loaded().site()).isEqualTo("US-TX-0417");
    }

    @Test
    void loadingAMissingConfigStops() {
        CliExit caught = catchThrowableOfType(
                CliExit.class, () -> context(tmp.resolve("absent.yaml")).loaded());
        assertThat(caught.code()).isEqualTo(Cli.EXIT_CONFIG);
    }

    @Test
    void failingNeverReturns() {
        CliExit caught = catchThrowableOfType(CliExit.class, () -> context(tmp).fail("gone wrong"));
        assertThat(caught.code()).isEqualTo(Cli.EXIT_FAILED);
        assertThat(err().strip()).isEqualTo("gone wrong");
    }

    @Test
    void readingAMissingArchiveIsRefused() throws IOException {
        Path target = archiveConfig(true);
        assertThatThrownBy(() -> context(target).readingArchive("query")).isInstanceOf(CliExit.class);
    }

    @Test
    void readingAnArchiveGivesBackTheConfigAndAConnection() throws IOException {
        CliContext.ReadArchive opened = context(made()).readingArchive("query");
        try (ArchiveDb connection = opened.connection()) {
            assertThat(opened.config().site()).isEqualTo("US-TX-0417");
            assertThat(connection
                            .queryOne("SELECT COUNT(*) AS n FROM records")
                            .orElseThrow()
                            .getLong("n"))
                    .isZero();
        }
    }

    @Test
    void aSiteWithNoArchiveStopsWithTheVerbItWasGiven() throws IOException {
        Path target = archiveConfig(false);
        assertThatThrownBy(() -> context(target).readingArchive("count")).isInstanceOf(CliExit.class);
        assertThat(err()).contains("nothing to count");
    }

    @Test
    void theConnectionItHandsBackIsReadOnly() throws IOException {
        // The collector is writing to this file while a report runs against it, so
        // a writable connection here would take the lock that stops the site.
        CliContext.ReadArchive opened = context(made()).readingArchive("query");
        try (ArchiveDb connection = opened.connection()) {
            SqlFailure caught = catchThrowableOfType(SqlFailure.class, () -> connection.execute("DELETE FROM records"));
            assertThat(caught.kind()).isEqualTo(SqlFailure.Kind.OPERATIONAL);
        }
    }

    @Test
    void aMissingConfigStopsBeforeTheArchive() {
        CliExit caught = catchThrowableOfType(
                CliExit.class, () -> context(tmp.resolve("absent.yaml")).readingArchive("query"));
        assertThat(caught.code()).isEqualTo(Cli.EXIT_CONFIG);
    }

    @Test
    void theVersionCommandPrintsTheVersionAlone() {
        assertThat(invoke("version").output().strip()).isEqualTo(Version.VERSION);
    }

    @Test
    void theFullVersionNamesEveryMovingNumber() {
        String output = invoke("version", "--full").output();
        for (String word : List.of("stanchion", "spool format", "archive", "java", "platform")) {
            assertThat(output).contains(word);
        }
    }

    @Test
    void theFullVersionIsOneFactPerLine() {
        assertThat(invoke("version", "--full").output().strip().lines()).hasSize(5);
    }

    @Test
    void theVersionCanComeBackAsJson() {
        Map<String, Object> payload =
                Json.loadsObject(invoke("version", "--json").output());
        assertThat(payload)
                .containsEntry("version", Version.VERSION)
                .containsEntry("spool_format", (long) Version.SPOOL_FORMAT)
                .containsEntry("archive_schema", (long) Schema.SCHEMA_VERSION);
    }

    @Test
    void theBuildReportsTheRunningJava() {
        Build built = Build.current();
        assertThat(built.java()).startsWith(Integer.toString(Runtime.version().feature()));
        assertThat(built.platform()).isNotEmpty();
    }

    @Test
    void aBuildWithNothingFilledInStillRenders() {
        assertThat(new Build().toString()).contains("stanchion");
    }
}
