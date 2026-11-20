package io.github.rkaushikdev.trammelworks.cli;

import static io.github.rkaushikdev.trammelworks.cli.CliFixtures.posix;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.spool.Budget;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import io.github.rkaushikdev.trammelworks.spool.Spool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliSpoolTest {

    static final PriorityTable TABLE = new PriorityTable(Map.of("payment-kernel", "payment", "price-sign", "chatter"));

    @TempDir
    Path tmp;

    Path spoolDir;
    Path config;

    @BeforeEach
    void setUp() {
        spoolDir = tmp.resolve("spool");
        config = CliFixtures.writeConfig(
                tmp,
                "site: US-TX-0417\n"
                        + "spool:\n"
                        + "  directory: " + posix(spoolDir) + "\n"
                        + "  budget: 64KiB\n"
                        + "  segment: 1KiB\n"
                        + "  priority:\n"
                        + "    units:\n"
                        + "      payment-kernel: payment\n"
                        + "      price-sign: chatter\n");
    }

    void fill(String unit, int count) {
        Spool store = Spool.builder(spoolDir, new Budget(64 * 1024, 0.1))
                .table(TABLE)
                .segmentBytes(1024)
                .build();
        for (int index = 0; index < count; index++) {
            store.append(new LogRecord(
                    "US-TX-0417",
                    unit,
                    Stream.STDOUT,
                    NanoTime.parse("2025-03-04T12:00:00Z"),
                    "line " + index + " " + "x".repeat(40)));
        }
        store.sealAll();
    }

    CliRunner.Result invoke(String... args) {
        String[] full = new String[args.length + 1];
        full[0] = "spool";
        System.arraycopy(args, 0, full, 1, args.length);
        return CliFixtures.invoke(config, full);
    }

    @Test
    void anEmptySpoolReportsZero() {
        CliRunner.Result result = invoke();
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("spool 0/65536 bytes");
    }

    @Test
    void theUsableSizeIsShown() {
        assertThat(invoke().output()).contains("usable");
    }

    @Test
    void everyClassIsListed() {
        assertThat(invoke().output()).contains("payment").contains("standard").contains("chatter");
    }

    @Test
    void theProtectedClassIsMarked() {
        assertThat(invoke().output()).contains("protected");
    }

    @Test
    void theUsedBytesFollowWhatWasWritten() {
        fill("price-sign", 6);
        assertThat(invoke().output()).doesNotContain("spool 0/");
    }

    @Test
    void theClassBreakdownShowsWhereItWent() {
        fill("payment-kernel", 6);
        List<String> lines = invoke().output()
                .lines()
                .filter(line -> line.strip().startsWith("payment"))
                .toList();
        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0)).doesNotContain("0B");
    }

    @Test
    void evictableExcludesTheProtectedClass() {
        fill("payment-kernel", 6);
        assertThat(invoke().output()).contains("evictable 0B");
    }

    @Test
    void evictableCountsALowClass() {
        fill("price-sign", 6);
        assertThat(invoke().output()).doesNotContain("evictable 0B");
    }

    @Test
    void segmentsAreListedOnRequest() {
        fill("price-sign", 6);
        assertThat(invoke("--segments").output()).contains("-chatter.spool");
    }

    @Test
    void anEmptySegmentListSaysSo() {
        assertThat(invoke("--segments").output()).contains("no segments");
    }

    @Test
    void verifyReadsEverySegment() {
        fill("price-sign", 6);
        assertThat(invoke("--verify").output()).contains("0 damaged").contains("records");
    }

    @Test
    void verifyReportsADamagedSegment() throws IOException {
        fill("price-sign", 6);
        Path victim = CliFixtures.files(spoolDir, ".spool").get(0);
        byte[] data = Files.readAllBytes(victim);
        data[10] ^= (byte) 0xFF;
        Files.write(victim, data);
        assertThat(invoke("--verify").output()).contains("DAMAGED").contains("1 damaged");
    }

    @Test
    void aPlanListsWhatWouldGo() {
        fill("price-sign", 12);
        assertThat(invoke("--plan", "1KiB").output()).contains("to free 1KiB").contains("delete");
    }

    @Test
    void aPlanThatIsEnoughSaysSo() {
        fill("price-sign", 12);
        assertThat(invoke("--plan", "512B").output()).contains("enough");
    }

    @Test
    void aPlanThatIsNotEnoughReportsTheShortfall() {
        fill("payment-kernel", 12);
        assertThat(invoke("--plan", "8KiB").output()).contains("short by").contains("protected and cannot go");
    }

    @Test
    void aMalformedPlanSizeIsRefused() {
        assertThat(invoke("--plan", "loads").exitCode()).isNotEqualTo(Cli.EXIT_OK);
    }

    @Test
    void aMissingConfigFails() {
        assertThat(CliRunner.invoke("--config", tmp.resolve("nope.yaml").toString(), "spool")
                        .output())
                .contains("stanchion.config");
    }
}
