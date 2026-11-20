package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.cli.CliRunner;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeployTest {

    @TempDir
    Path tmp;

    SiteConfig config;
    Path configFile;

    @BeforeEach
    void setUp() throws IOException {
        config = configFor(ArchiveSettings.at("/var/lib/stanchion/archive/records.sqlite3"));
        configFile = tmp.resolve("site.yaml");
        Files.writeString(
                configFile,
                "site: US-TX-0417\n"
                        + "ingest:\n"
                        + "  log_root: /var/log/containers\n"
                        + "spool:\n"
                        + "  directory: /var/lib/stanchion/spool\n"
                        + "archive:\n"
                        + "  path: /var/lib/stanchion/archive/records.sqlite3\n",
                StandardCharsets.UTF_8);
    }

    static SiteConfig configFor(ArchiveSettings archive) {
        return SiteConfig.builder("US-TX-0417")
                .ingest(IngestConfig.at("/var/log/containers"))
                .spool(SpoolSettings.at("/var/lib/stanchion/spool"))
                .archive(archive)
                .build();
    }

    static String bodyOf(List<SystemdUnit> units, String name) {
        return units.stream()
                .filter(unit -> unit.name().equals(name))
                .findFirst()
                .orElseThrow()
                .body();
    }

    CliRunner.Result invoke(String... args) {
        String[] full = new String[args.length + 3];
        full[0] = "--config";
        full[1] = configFile.toString();
        full[2] = "units";
        System.arraycopy(args, 0, full, 3, args.length);
        return CliRunner.invoke(full);
    }

    static List<Path> listed(Path directory) throws IOException {
        try (Stream<Path> found = Files.list(directory)) {
            return found.toList();
        }
    }

    @Test
    void aRelativeConfigPathIsRefused() {
        assertThatThrownBy(() -> new Deployment().withConfigPath("site.yaml"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be absolute");
    }

    @Test
    void aRelativeExecutableIsRefused() {
        assertThatThrownBy(() -> new Deployment().withExecutable("stanchion"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be absolute");
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "25:00", "03:99", "morning", "03-20"})
    void aTimeThatIsNotATimeIsRefused(String bad) {
        assertThatThrownBy(() -> new Deployment().withHousekeepAt(bad))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("HH:MM");
    }

    @Test
    void aSensibleTimeIsTaken() {
        assertThat(new Deployment().withHousekeepAt("23:59").housekeepAt()).isEqualTo("23:59");
    }

    @Test
    void theWritablePathsCoverTheSpoolAndItsState() {
        assertThat(Deploy.writablePaths(config)).contains("/var/lib/stanchion/spool", "/var/lib/stanchion/state");
    }

    @Test
    void theWritablePathsCoverTheArchiveDirectory() {
        assertThat(Deploy.writablePaths(config)).contains("/var/lib/stanchion/archive");
    }

    @Test
    void aSiteWithNoArchiveDoesNotAskToWriteOne() {
        SiteConfig without = configFor(ArchiveSettings.builder().enabled(false).build());
        assertThat(Deploy.writablePaths(without)).noneMatch(item -> item.contains("archive"));
    }

    @Test
    void theWritablePathsHaveNoDuplicates() {
        List<String> found = Deploy.writablePaths(config);
        assertThat(new HashSet<>(found)).hasSameSizeAs(found);
    }

    @Test
    void theCollectorUnitRunsTheRunCommand() {
        assertThat(Deploy.collectUnit(config, new Deployment()).body())
                .contains("ExecStart=/usr/local/bin/stanchion --config /etc/stanchion/site.yaml run");
    }

    @Test
    void theCollectorUnitNamesTheSite() {
        assertThat(Deploy.collectUnit(config, new Deployment()).body()).contains("US-TX-0417");
    }

    @Test
    void theCollectorRestartsAlways() {
        assertThat(Deploy.collectUnit(config, new Deployment()).body()).contains("Restart=always");
    }

    @Test
    void theCollectorWaitsForTheRuntime() {
        assertThat(Deploy.collectUnit(config, new Deployment()).body())
                .contains("After=containerd.service docker.service");
    }

    @Test
    void everyServiceUnitIsHardened() {
        List<BiFunction<SiteConfig, Deployment, SystemdUnit>> makers =
                List.of(Deploy::collectUnit, Deploy::housekeepUnit, Deploy::serveUnit);
        for (BiFunction<SiteConfig, Deployment, SystemdUnit> maker : makers) {
            String body = maker.apply(config, new Deployment()).body();
            for (String line : Deploy.COMMON_HARDENING) {
                assertThat(body).contains(line);
            }
        }
    }

    @Test
    void theLogRootIsBoundReadOnly() {
        assertThat(Deploy.collectUnit(config, new Deployment()).body())
                .contains("BindReadOnlyPaths=/var/log/containers");
    }

    @Test
    void theSpoolIsWritable() {
        assertThat(Deploy.collectUnit(config, new Deployment()).body())
                .contains("ReadWritePaths=")
                .contains("/var/lib/stanchion/spool");
    }

    @Test
    void theLogRootIsNotWritable() {
        String line = Deploy.collectUnit(config, new Deployment())
                .body()
                .lines()
                .filter(row -> row.startsWith("ReadWritePaths="))
                .findFirst()
                .orElseThrow();
        assertThat(line).doesNotContain("/var/log/containers");
    }

    @Test
    void theHousekeepUnitIsAOneshot() {
        assertThat(Deploy.housekeepUnit(config, new Deployment()).body()).contains("Type=oneshot");
    }

    @Test
    void theHousekeepUnitRunsTheHousekeepCommand() {
        assertThat(Deploy.housekeepUnit(config, new Deployment()).body()).contains(" housekeep");
    }

    @Test
    void theTimerUsesTheHourItWasGiven() {
        assertThat(Deploy.housekeepTimer(new Deployment().withHousekeepAt("04:05"))
                        .body())
                .contains("OnCalendar=*-*-* 04:05:00");
    }

    @Test
    void theTimerCatchesUpAfterAPowerCut() {
        assertThat(Deploy.housekeepTimer(new Deployment()).body()).contains("Persistent=yes");
    }

    @Test
    void theTimerSpreadsTheEstateOut() {
        assertThat(Deploy.housekeepTimer(new Deployment()).body()).contains("RandomizedDelaySec=");
    }

    @Test
    void threeUnitsByDefault() {
        assertThat(Deploy.unitsFor(config))
                .extracting(SystemdUnit::name)
                .containsExactly(
                        "stanchion-collect.service", "stanchion-housekeep.service", "stanchion-housekeep.timer");
    }

    @Test
    void theServiceIsAddedWhenAsked() {
        assertThat(Deploy.unitsFor(config, new Deployment().withServe(true)))
                .extracting(SystemdUnit::name)
                .contains("stanchion-serve.service");
    }

    @Test
    void theServeUnitRestartsOnlyOnFailure() {
        assertThat(Deploy.serveUnit(config, new Deployment()).body()).contains("Restart=on-failure");
    }

    @Test
    void aUnitKnowsWhereItBelongs() {
        SystemdUnit unit = Deploy.collectUnit(config, new Deployment());
        assertThat(unit.path()).isEqualTo("/etc/systemd/system/stanchion-collect.service");
        assertThat(unit).hasToString("stanchion-collect.service");
    }

    @Test
    void everyUnitEndsWithANewline() {
        for (SystemdUnit unit : Deploy.unitsFor(config, new Deployment().withServe(true))) {
            assertThat(unit.body()).endsWith("\n");
        }
    }

    @Test
    void everyUnitHasAnInstallSectionExceptTheOneshot() {
        for (SystemdUnit unit : Deploy.unitsFor(config, new Deployment().withServe(true))) {
            if (unit.name().equals("stanchion-housekeep.service")) {
                assertThat(unit.body()).doesNotContain("[Install]");
            } else {
                assertThat(unit.body()).contains("[Install]");
            }
        }
    }

    @Test
    void unitsAreWrittenWhereAsked() {
        List<Path> written = Deploy.writeUnits(Deploy.unitsFor(config), tmp.resolve("units"));
        assertThat(written).hasSize(3).allMatch(Files::exists);
    }

    @Test
    void writingCreatesTheDirectory() {
        Deploy.writeUnits(Deploy.unitsFor(config), tmp.resolve("deep").resolve("units"));
        assertThat(tmp.resolve("deep").resolve("units")).isDirectory();
    }

    @Test
    void theWrittenUnitMatchesTheGeneratedOne() throws IOException {
        SystemdUnit unit = Deploy.unitsFor(config).get(0);
        Path written = Deploy.writeUnits(List.of(unit), tmp.resolve("units")).get(0);
        assertThat(Files.readString(written, StandardCharsets.UTF_8)).isEqualTo(unit.body());
    }

    @Test
    void theCommandPrintsTheUnits() {
        CliRunner.Result result = invoke();
        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("/etc/systemd/system/stanchion-collect.service")
                .contains("ExecStart=");
    }

    @Test
    void theCommandNamesTheConfigPathItWasInvokedWith() {
        // A unit file describes a Linux box, so the path in it has to be POSIX absolute. Where the
        // temporary config already is one it is used as given; elsewhere the command falls back to
        // the installed path with a note.
        CliRunner.Result result = invoke();
        String here = configFile.toString();
        if (here.startsWith("/")) {
            assertThat(result.output()).contains("--config " + here).doesNotContain("pass --config-path");
        } else {
            assertThat(result.output())
                    .contains("--config /etc/stanchion/site.yaml")
                    .contains("pass --config-path to change it");
        }
    }

    @Test
    void aConfigPathThatNoUnitCouldUseFallsBack() {
        CliRunner.Result result = invoke("--config-path", "site.yaml");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("must be absolute");
    }

    @Test
    void theCommandKeepsAnAbsolutePathItWasGiven() {
        assertThat(invoke("--config-path", "/etc/stanchion/site.yaml").output())
                .contains("--config /etc/stanchion/site.yaml")
                .doesNotContain("pass --config-path");
    }

    @Test
    void aUnitNeverCarriesABackslash() throws IOException {
        // These files describe a Linux box whatever generated them, so the written units are what
        // this checks rather than the notes on the way past.
        invoke("--to", tmp.resolve("units").toString());
        for (Path path : listed(tmp.resolve("units"))) {
            assertThat(Files.readString(path, StandardCharsets.UTF_8)).doesNotContain("\\");
        }
    }

    @Test
    void theCommandCanBeToldAnotherConfigPath() {
        assertThat(invoke("--config-path", "/etc/stanchion/other.yaml").output())
                .contains("--config /etc/stanchion/other.yaml");
    }

    @Test
    void theCommandWritesToADirectory() {
        assertThat(invoke("--to", tmp.resolve("units").toString()).output())
                .contains("3 units written, none installed");
        assertThat(tmp.resolve("units").resolve("stanchion-collect.service")).exists();
    }

    @Test
    void theCommandAddsTheServiceWhenAsked() {
        assertThat(invoke("--serve").output()).contains("stanchion-serve.service");
    }

    @Test
    void theCommandTakesAUser() {
        assertThat(invoke("--user", "forecourt").output())
                .contains("User=forecourt")
                .contains("Group=forecourt");
    }

    @Test
    void aBadTimeIsRefusedByTheCommand() {
        CliRunner.Result result = invoke("--at", "half three");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("HH:MM");
    }

    @Test
    void nothingIsInstalledByTheCommand() {
        // The command must never touch the real unit directory.
        invoke("--to", tmp.resolve("units").toString());
        assertThat(Path.of("/etc/systemd/system/stanchion-collect.service")).doesNotExist();
    }
}
