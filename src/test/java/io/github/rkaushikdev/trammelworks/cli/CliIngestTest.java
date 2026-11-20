package io.github.rkaushikdev.trammelworks.cli;

import static io.github.rkaushikdev.trammelworks.LogFiles.criFull;
import static io.github.rkaushikdev.trammelworks.LogFiles.criLine;
import static io.github.rkaushikdev.trammelworks.cli.CliFixtures.posix;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.SiteTree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliIngestTest {

    static final String TS = "2025-03-04T11:30:00.123456789Z";

    @TempDir
    Path tmp;

    SiteTree tree;
    Path config;

    @BeforeEach
    void setUp() throws IOException {
        tree = new SiteTree(tmp.resolve("log"));
        Files.createDirectories(tree.root());
        config = CliFixtures.writeConfig(tmp, "site: US-TX-0417\ningest:\n  log_root: " + posix(tree.root()) + "\n");
    }

    CliRunner.Result invoke(String... args) {
        return CliFixtures.invoke(config, args);
    }

    void stray() throws IOException {
        Files.writeString(tree.root().resolve("messages"), "not ours", StandardCharsets.UTF_8);
    }

    static int occurrences(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    @Test
    void discoverNamesTheLogRoot() {
        CliRunner.Result result = invoke("discover");
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains(posix(tree.root()));
    }

    @Test
    void discoverListsAFollowedUnit() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        assertThat(invoke("discover").output()).contains("follow  pump-bridge").contains("cri");
    }

    @Test
    void discoverSaysSoWhenThereIsNothing() {
        assertThat(invoke("discover").output()).contains("follow  nothing");
    }

    @Test
    void discoverReportsSkipsWithAReason() throws IOException {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        stray();
        assertThat(invoke("discover").output()).contains("skipped unparseable");
    }

    @Test
    void discoverCanHideTheSkips() throws IOException {
        stray();
        assertThat(invoke("discover", "--no-skipped").output()).doesNotContain("skipped");
    }

    @Test
    void tailPrintsARecord() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "nozzle 1 lifted")));
        assertThat(invoke("tail").output()).contains("nozzle 1 lifted").contains("pump-bridge stdout");
    }

    @Test
    void tailPrintsTheTimestampInFull() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "x")));
        assertThat(invoke("tail").output()).contains(TS);
    }

    @Test
    void tailJoinsSplitChunks() {
        tree.writeCri(
                "pump-bridge", List.of(criLine(TS, "stdout", "P", "first "), criLine(TS, "stdout", "F", "second")));
        assertThat(invoke("tail").output()).contains("first second");
    }

    @Test
    void tailEscapesANewlineSoOneRecordIsOneLine() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "x".repeat(300))));
        assertThat(invoke("tail", "--full").output().strip().lines()).hasSize(1);
    }

    @Test
    void tailTruncatesByDefault() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "y".repeat(300))));
        assertThat(invoke("tail").output()).contains("y".repeat(160)).doesNotContain("y".repeat(200));
    }

    @Test
    void tailFullPrintsEverything() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "z".repeat(300))));
        assertThat(invoke("tail", "--full").output()).contains("z".repeat(300));
    }

    @Test
    void tailCanFilterToOneUnit() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "pump line")));
        tree.writeCri("tank-gauge", List.of(criFull(TS, "gauge line")));
        assertThat(invoke("tail", "--unit", "pump-bridge").output())
                .contains("pump line")
                .doesNotContain("gauge line");
    }

    @Test
    void tailFlushesAnUnfinishedLineAtTheEnd() {
        tree.writeCri("pump-bridge", List.of(criLine(TS, "stdout", "P", "never closed")));
        assertThat(invoke("tail").output()).contains("never closed");
    }

    @Test
    void tailRefusesZeroPasses() {
        CliRunner.Result result = invoke("tail", "--passes", "0");
        assertThat(result.exitCode()).isNotEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("at least 1");
    }

    @Test
    void tailWithSeveralPassesStillReadsOnce() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "only once")));
        assertThat(occurrences(invoke("tail", "--passes", "3").output(), "only once"))
                .isEqualTo(1);
    }

    @Test
    void verboseTailReportsACountOnStderr() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one")));
        assertThat(CliRunner.invoke("--config", config.toString(), "-v", "tail").stderr())
                .contains("1 records");
    }

    @Test
    void discoverOnAMissingConfigFails() {
        CliRunner.Result result =
                CliRunner.invoke("--config", tmp.resolve("nope.yaml").toString(), "discover");
        assertThat(result.exitCode()).isNotEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("stanchion.config");
    }
}
