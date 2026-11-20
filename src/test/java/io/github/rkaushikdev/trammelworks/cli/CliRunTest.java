package io.github.rkaushikdev.trammelworks.cli;

import static io.github.rkaushikdev.trammelworks.LogFiles.criFull;
import static io.github.rkaushikdev.trammelworks.cli.CliFixtures.posix;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.CollectReport;
import io.github.rkaushikdev.trammelworks.CycleReport;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.SiteTree;
import io.github.rkaushikdev.trammelworks.ship.ShipReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliRunTest {

    static final String TS = "2025-03-04T12:00:00.000000000Z";
    static final String VISA = "4111111111111111";

    @TempDir
    Path tmp;

    SiteTree tree;
    Path outbox;
    Path config;

    @BeforeEach
    void setUp() throws IOException {
        tree = new SiteTree(tmp.resolve("log"));
        outbox = tmp.resolve("outbox");
        Files.createDirectories(tree.root());
        config = CliFixtures.writeConfig(
                tmp,
                "site: US-TX-0417\n"
                        + "ingest:\n"
                        + "  log_root: " + posix(tree.root()) + "\n"
                        + "spool:\n"
                        + "  directory: " + posix(tmp.resolve("spool")) + "\n"
                        + "  budget: 1MiB\n"
                        + "  segment: 16KiB\n"
                        + "archive:\n"
                        + "  path: " + posix(tmp.resolve("archive").resolve("records.sqlite3")) + "\n"
                        + "ship:\n"
                        + "  endpoint: file://" + posix(outbox) + "\n"
                        + "  interval: 1s\n");
    }

    CliRunner.Result invoke(String... args) {
        return CliFixtures.invoke(config, args);
    }

    List<Path> shipped() {
        return CliFixtures.files(outbox, ".json");
    }

    @Test
    void totalsStartEmpty() {
        assertThat(new RunCommands.RunTotals().toString()).contains("0 cycles");
    }

    @Test
    void totalsAddACycleUp() {
        RunCommands.RunTotals totals = new RunCommands.RunTotals();
        totals.absorb(new CycleReport(new CollectReport(3, 3, 1), new ShipReport(3, 400, "")));
        assertThat(totals.toString()).contains("read 3").contains("sent 3 in 400 bytes");
    }

    @Test
    void totalsRememberWhyShippingStopped() {
        RunCommands.RunTotals totals = new RunCommands.RunTotals();
        totals.absorb(new CycleReport(new CollectReport(), new ShipReport(0, 0, "gateway")));
        assertThat(totals.stopped).isEqualTo("gateway");
    }

    @Test
    void aRunOnAnEmptySiteReportsNothing() {
        CliRunner.Result result = invoke("run");
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("1 cycles, read 0");
    }

    @Test
    void aRunCollectsAndShips() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "nozzle 1 lifted")));
        CliRunner.Result result = invoke("run", "--interval", "0");
        assertThat(result.output()).contains("read 1").contains("sent 1");
        assertThat(shipped()).isNotEmpty();
    }

    @Test
    void theShippedFileHoldsTheRecord() throws IOException {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "nozzle 1 lifted")));
        invoke("run", "--interval", "0");
        Map<String, Object> payload =
                Json.loadsObject(Files.readString(shipped().get(0), StandardCharsets.UTF_8));
        assertThat(firstRecord(payload)).containsEntry("message", "nozzle 1 lifted");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> firstRecord(Map<String, Object> payload) {
        return ((List<Map<String, Object>>) payload.get("records")).get(0);
    }

    @Test
    void aCardNumberDoesNotReachTheOutbox() throws IOException {
        tree.writeCri("payment-kernel", List.of(criFull(TS, "pan=" + VISA)));
        invoke("run", "--interval", "0");
        assertThat(Files.readString(shipped().get(0), StandardCharsets.UTF_8)).doesNotContain(VISA);
    }

    @Test
    void severalCyclesAreRun() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one")));
        assertThat(invoke("run", "--cycles", "3", "--interval", "0").output()).contains("3 cycles");
    }

    @Test
    void zeroCyclesIsRefused() {
        CliRunner.Result result = invoke("run", "--cycles", "0");
        assertThat(result.exitCode()).isNotEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("at least 1");
    }

    @Test
    void verboseReportsEachCycle() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one")));
        CliRunner.Result result = CliRunner.invoke("--config", config.toString(), "-v", "run", "--interval", "0");
        assertThat(result.output()).contains("cycle 1:");
    }

    @Test
    void aMissingConfigFails() {
        CliRunner.Result result =
                CliRunner.invoke("--config", tmp.resolve("nope.yaml").toString(), "run");
        assertThat(result.exitCode()).isNotEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("stanchion.config");
    }

    @Test
    void shipOnAnEmptySpoolSendsNothing() {
        assertThat(invoke("ship").output()).contains("sent 0 records");
    }

    @Test
    void shipDrainsWhatRunCollected() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one")));
        invoke("run", "--interval", "0");
        tree.writeCri("pump-bridge", List.of(criFull(TS, "two")));
        invoke("run", "--interval", "0");
        assertThat(shipped()).hasSize(2);
    }

    @Test
    void shipReportsTheMeter() {
        assertThat(invoke("ship").output()).contains("meter");
    }

    @Test
    void zeroPassesIsRefused() {
        CliRunner.Result result = invoke("ship", "--passes", "0");
        assertThat(result.exitCode()).isNotEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("at least 1");
    }

    @Test
    void shipStopsEarlyWhenThereIsNothingLeft() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one")));
        invoke("run", "--interval", "0");
        assertThat(invoke("ship", "--passes", "5").output()).contains("sent 0 records");
    }
}
