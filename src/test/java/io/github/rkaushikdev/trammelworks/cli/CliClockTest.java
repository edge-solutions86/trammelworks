package io.github.rkaushikdev.trammelworks.cli;

import static io.github.rkaushikdev.trammelworks.cli.CliFixtures.posix;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.clockfix.ClockStore;
import io.github.rkaushikdev.trammelworks.clockfix.SessionLedger;
import io.github.rkaushikdev.trammelworks.clockfix.SyncEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliClockTest {

    static final String BOOT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d";
    static final String OTHER = "aa".repeat(16);
    static final long NS_PER_SECOND = 1_000_000_000L;

    @TempDir
    Path tmp;

    Path config;
    ClockStore store;

    @BeforeEach
    void setUp() {
        Path spool = tmp.resolve("spool");
        config = CliFixtures.writeConfig(tmp, "site: US-TX-0417\nspool:\n  directory: " + posix(spool) + "\n");
        store = new ClockStore(ClockCommand.stateDirectory(posix(spool)));
    }

    static SyncEvent sync(String bootId, long monotonicNs, double errorSeconds) {
        NanoTime truth = NanoTime.parse("2025-03-04T12:00:00Z");
        return new SyncEvent(
                bootId, monotonicNs, truth.shifted(-(long) (errorSeconds * NS_PER_SECOND)), truth, "chrony");
    }

    CliRunner.Result invoke(String... args) {
        String[] full = new String[args.length + 1];
        full[0] = "clock";
        System.arraycopy(args, 0, full, 1, args.length);
        return CliFixtures.invoke(config, full);
    }

    void save(SyncEvent... events) {
        SessionLedger ledger = new SessionLedger();
        for (SyncEvent event : events) {
            ledger.addSync(event);
        }
        store.save(ledger);
    }

    @Test
    void theStateDirSitsBesideTheSpool() {
        assertThat(ClockCommand.stateDirectory("/var/lib/stanchion/spool"))
                .isEqualTo(Path.of("/var/lib/stanchion/state"));
    }

    @Test
    void theStateDirIsNeverInsideTheSpool() {
        assertThat(ClockCommand.stateDirectory("/var/lib/stanchion/spool")
                        .getFileName()
                        .toString())
                .doesNotContain("spool");
    }

    @Test
    void anEmptyLedgerSaysSo() {
        CliRunner.Result result = invoke();
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("no boot sessions recorded");
    }

    @Test
    void theSiteIsNamed() {
        save(sync(BOOT, 600 * NS_PER_SECOND, 7));
        assertThat(invoke().output()).contains("site US-TX-0417");
    }

    @Test
    void theBootIdIsShown() {
        save(sync(BOOT, 600 * NS_PER_SECOND, 7));
        assertThat(invoke().output()).contains(BOOT);
    }

    @Test
    void aSyncIsListedWithItsDriftInMilliseconds() {
        save(sync(BOOT, 600 * NS_PER_SECOND, 7));
        assertThat(invoke().output()).contains("chrony").contains("+7000.0 ms");
    }

    @Test
    void aLargeCorrectionIsFlagged() {
        save(sync(BOOT, 600 * NS_PER_SECOND, 7200));
        assertThat(invoke().output()).contains("LARGE");
    }

    @Test
    void aSmallCorrectionIsNotFlagged() {
        save(sync(BOOT, 600 * NS_PER_SECOND, 1));
        assertThat(invoke().output()).doesNotContain("LARGE");
    }

    @Test
    void oneSyncGivesNoRate() {
        save(sync(BOOT, 600 * NS_PER_SECOND, 7));
        assertThat(invoke().output()).contains("rate unknown");
    }

    @Test
    void twoSyncsGiveARateInPpm() {
        save(sync(BOOT, 0, 0), sync(BOOT, 1000 * NS_PER_SECOND, 1));
        assertThat(invoke().output()).contains("+1000.0 ppm");
    }

    @Test
    void onlyTheLatestBootIsShownByDefault() {
        save(sync(BOOT, 600 * NS_PER_SECOND, 7), sync(OTHER, 600 * NS_PER_SECOND, 3));
        assertThat(invoke().output()).contains(OTHER).doesNotContain(BOOT);
    }

    @Test
    void allBootsShowsEveryOne() {
        save(sync(BOOT, 600 * NS_PER_SECOND, 7), sync(OTHER, 600 * NS_PER_SECOND, 3));
        assertThat(invoke("--all-boots").output()).contains(BOOT).contains(OTHER);
    }

    @Test
    void theRestartCountIsReported() {
        SessionLedger ledger = new SessionLedger();
        ledger.session(BOOT);
        ledger.session(OTHER);
        store.save(ledger);
        assertThat(invoke().output()).contains("2 boots remembered, 1 restarts");
    }

    @Test
    void aCorruptLedgerIsReportedAndNotFatal() throws IOException {
        SessionLedger ledger = new SessionLedger();
        ledger.session(BOOT);
        store.save(ledger);
        Files.writeString(store.path(), "{broken", StandardCharsets.UTF_8);
        CliRunner.Result result = invoke();
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("no boot sessions recorded");
    }

    @Test
    void aMissingConfigFailsCleanly() {
        CliRunner.Result result =
                CliRunner.invoke("--config", tmp.resolve("nope.yaml").toString(), "clock");
        assertThat(result.exitCode()).isNotEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("stanchion.config");
    }
}
