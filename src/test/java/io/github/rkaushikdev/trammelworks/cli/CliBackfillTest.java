package io.github.rkaushikdev.trammelworks.cli;

import static io.github.rkaushikdev.trammelworks.LogFiles.criFull;
import static io.github.rkaushikdev.trammelworks.cli.CliFixtures.posix;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.SiteTree;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliBackfillTest {

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
                        + "  endpoint: file://" + posix(outbox) + "\n");
    }

    Path seed(Map<String, String> rotations, boolean compress) throws IOException {
        Path live = tree.writeCri("pump-bridge", List.of(criFull(TS, "the live line")));
        for (Map.Entry<String, String> entry : rotations.entrySet()) {
            Path target = live.resolveSibling(live.getFileName() + entry.getKey());
            byte[] body = entry.getValue().getBytes(StandardCharsets.UTF_8);
            if (compress) {
                ByteArrayOutputStream packed = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(packed)) {
                    gzip.write(body);
                }
                body = packed.toByteArray();
            }
            Files.write(target, body);
        }
        return live;
    }

    Path seed(String suffix, String text) throws IOException {
        return seed(Map.of(suffix, text), false);
    }

    CliRunner.Result invoke(String... args) {
        String[] full = new String[args.length + 1];
        full[0] = "backfill";
        System.arraycopy(args, 0, full, 1, args.length);
        return CliFixtures.invoke(config, full);
    }

    @Test
    void aSiteWithNothingFollowedSaysSo() {
        CliRunner.Result result = invoke();
        assertThat(result.exitCode()).isEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("nothing to backfill");
    }

    @Test
    void aDryRunListsTheRotatedFiles() throws IOException {
        Map<String, String> rotations = new LinkedHashMap<>();
        rotations.put(".1", criFull(TS, "older"));
        rotations.put(".2", criFull(TS, "oldest"));
        seed(rotations, false);
        CliRunner.Result result = invoke("--dry-run");
        assertThat(result.output())
                .contains("pump-bridge")
                .contains("2 rotated files")
                .contains("would be read");
    }

    @Test
    void aDryRunReadsNothing() throws IOException {
        seed(".1", criFull(TS, "older"));
        invoke("--dry-run");
        assertThat(CliFixtures.files(tmp.resolve("spool"), ".spool")).isEmpty();
    }

    @Test
    void aBackfillPutsRecordsInTheSpool() throws IOException {
        seed(".1", criFull(TS, "older") + criFull(TS, "older still"));
        assertThat(invoke().output()).contains("2 records backfilled");
        assertThat(CliFixtures.files(tmp.resolve("spool"), ".spool")).isNotEmpty();
    }

    @Test
    void aBackfillReportsPerUnit() throws IOException {
        seed(".1", criFull(TS, "older"));
        assertThat(invoke().output()).contains("backfilled 1 records from 1 files");
    }

    @Test
    void aCompressedRotationIsRead() throws IOException {
        seed(Map.of(".1.gz", criFull(TS, "packed")), true);
        assertThat(invoke().output()).contains("1 records backfilled");
    }

    @Test
    void aBackfilledRecordIsRedacted() throws IOException {
        seed(".1", criFull(TS, "pan=" + VISA));
        invoke();
        StringBuilder onDisk = new StringBuilder();
        for (Path path : CliFixtures.files(tmp.resolve("spool"), ".spool")) {
            onDisk.append(new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1));
        }
        assertThat(onDisk.toString()).doesNotContain(VISA);
    }

    @Test
    void aBackfilledRecordReachesTheArchive() throws IOException {
        seed(".1", criFull(TS, "older"));
        invoke();
        try (ArchiveDb connection = Schema.openArchive(tmp.resolve("archive").resolve("records.sqlite3"))) {
            assertThat(new Repository(connection).count()).isEqualTo(1);
        }
    }

    @Test
    void theUnitFilterNarrows() throws IOException {
        seed(".1", criFull(TS, "older"));
        tree.writeCri("tank-gauge", List.of(criFull(TS, "live gauge")));
        assertThat(invoke("--unit", "tank-gauge").output()).doesNotContain("pump-bridge");
    }

    @Test
    void aSizeCapCanBeGiven() throws IOException {
        seed(".1", criFull(TS, "older"));
        assertThat(invoke("--max-bytes", "1KiB").exitCode()).isEqualTo(Cli.EXIT_OK);
    }

    @Test
    void aFileOverTheCapIsReportedNotRead() throws IOException {
        seed(".1", criFull(TS, "x".repeat(500)));
        assertThat(invoke("--max-bytes", "100B").output()).contains("0 records backfilled");
    }

    @Test
    void aBadSizeIsRefused() throws IOException {
        seed(".1", criFull(TS, "older"));
        assertThat(invoke("--max-bytes", "loads").exitCode()).isNotEqualTo(Cli.EXIT_OK);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theBackfilledRecordsCanThenBeShipped() throws IOException {
        seed(".1", criFull(TS, "older"));
        invoke();
        CliFixtures.invoke(config, "ship");
        List<Path> written = CliFixtures.files(outbox, ".json");
        assertThat(written).isNotEmpty();
        Map<String, Object> payload = Json.loadsObject(Files.readString(written.get(0), StandardCharsets.UTF_8));
        assertThat((List<Map<String, Object>>) payload.get("records"))
                .anyMatch(item -> "older".equals(item.get("message")));
    }

    @Test
    void aMissingConfigFails() {
        assertThat(CliRunner.invoke("--config", tmp.resolve("nope.yaml").toString(), "backfill")
                        .output())
                .contains("stanchion.config");
    }
}
