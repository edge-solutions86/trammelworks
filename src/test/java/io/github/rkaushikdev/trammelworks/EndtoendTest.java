package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import io.github.rkaushikdev.trammelworks.ingest.CriLine;
import io.github.rkaushikdev.trammelworks.redact.Pans;
import io.github.rkaushikdev.trammelworks.redact.Redactor;
import io.github.rkaushikdev.trammelworks.ship.Batch;
import io.github.rkaushikdev.trammelworks.ship.MemoryTransport;
import io.github.rkaushikdev.trammelworks.ship.ShipSettings;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole thing, over a simulated shift, at a volume where the pieces interact: segments roll,
 * the joiner spans a poll boundary, the meter runs down, records come out the other end. Every line
 * the containers wrote reaches the collector once, no card number reaches the spool, the archive or
 * the wire, and a restart loses nothing and repeats nothing.
 */
class EndtoendTest {

    static final int CORPUS_SIZE = 600;
    static final PriorityTable TABLE =
            new PriorityTable(Map.of("payment-kernel", "payment", "price-sign", "chatter", "tank-gauge", "chatter"));

    @TempDir
    Path seeded;

    final List<Site> built = new ArrayList<>();

    @BeforeEach
    void setUp() {
        new Simulation(101).writeCri(seeded.resolve("log"), CORPUS_SIZE);
    }

    @AfterEach
    void tearDown() {
        built.forEach(Site::release);
    }

    SiteConfig buildConfig() {
        return SiteConfig.builder("US-TX-0417")
                .ingest(IngestConfig.at(PosixPath.asPosix(seeded.resolve("log"))))
                .spool(SpoolSettings.builder()
                        .directory(PosixPath.asPosix(seeded.resolve("spool")))
                        .budgetBytes(4L * 1024 * 1024)
                        .segmentBytes(32L * 1024)
                        .priority(TABLE)
                        .build())
                .archive(ArchiveSettings.at(
                        PosixPath.asPosix(seeded.resolve("archive").resolve("records.sqlite3"))))
                .ship(ShipSettings.builder()
                        .monthlyAllowanceBytes(50L * 1024 * 1024)
                        .batchRecords(50)
                        .build())
                .build();
    }

    Site buildSite(MemoryTransport transport) {
        ManualClock clock = new ManualClock();
        clock.setWall("2025-03-04T18:00:00Z");
        Site site = Site.build(buildConfig(), transport, clock, null, null);
        built.add(site);
        return site;
    }

    /**
     * Every application line the corpus contains, as it should come out: joined the way the
     * runtime split it, and redacted, because redacted is what the site is supposed to deliver.
     */
    List<String> expectedLines() throws IOException {
        Redactor redactor = new Redactor();
        List<String> out = new ArrayList<>();
        List<Path> logs;
        try (Stream<Path> walked = Files.walk(seeded.resolve("log"))) {
            logs = walked.filter(path -> path.toString().endsWith(".log"))
                    .sorted()
                    .toList();
        }
        for (Path path : logs) {
            StringBuilder pending = new StringBuilder();
            for (byte[] raw : byteLines(Files.readAllBytes(path))) {
                CriLine line = CriLine.parse(raw);
                pending.append(line.message());
                if (!line.partial()) {
                    out.add(redactor.redactText(pending.toString()).text());
                    pending.setLength(0);
                }
            }
            if (!pending.isEmpty()) {
                out.add(redactor.redactText(pending.toString()).text());
            }
        }
        return out;
    }

    /** Lines of bytes split at a newline, a carriage return or both, the ending left off. */
    static List<byte[]> byteLines(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < data.length; index++) {
            if (data[index] == '\n' || data[index] == '\r') {
                out.add(java.util.Arrays.copyOfRange(data, start, index));
                if (data[index] == '\r' && index + 1 < data.length && data[index + 1] == '\n') {
                    index++;
                }
                start = index + 1;
            }
        }
        if (start < data.length) {
            out.add(java.util.Arrays.copyOfRange(data, start, data.length));
        }
        return out;
    }

    static List<String> delivered(MemoryTransport transport) {
        List<String> out = new ArrayList<>();
        for (Batch batch : transport.received()) {
            batch.records().forEach(item -> out.add((String) item.get("message")));
        }
        return out;
    }

    static List<String> sorted(List<String> items) {
        List<String> out = new ArrayList<>(items);
        out.sort(Json::compareCodePoints);
        return out;
    }

    Site shipped(MemoryTransport transport) {
        Site site = buildSite(transport);
        site.open();
        site.cycle();
        site.close();
        site.ship();
        return site;
    }

    List<Path> segments() {
        try (Stream<Path> found = Files.list(seeded.resolve("spool"))) {
            return found.filter(path -> path.toString().endsWith(".spool")).toList();
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    @Test
    void everyLineReachesTheCollectorOnce() throws IOException {
        MemoryTransport transport = new MemoryTransport();
        shipped(transport);
        assertThat(sorted(delivered(transport))).isEqualTo(sorted(expectedLines()));
    }

    @Test
    void nothingIsDeliveredTwice() throws IOException {
        MemoryTransport transport = new MemoryTransport();
        Site site = buildSite(transport);
        site.open();
        for (int round = 0; round < 3; round++) {
            site.cycle();
        }
        site.close();
        site.ship();
        assertThat(delivered(transport)).hasSameSizeAs(expectedLines());
    }

    @Test
    void theSpoolEmpties() {
        Site site = buildSite(new MemoryTransport());
        site.open();
        site.cycle();
        site.close();
        for (int round = 0; round < 10; round++) {
            if (site.ship().idle()) {
                break;
            }
        }
        assertThat(segments()).isEmpty();
    }

    @Test
    void noCardNumberReachesTheWire() {
        MemoryTransport transport = new MemoryTransport();
        shipped(transport);
        for (Batch batch : transport.received()) {
            assertThat(Pans.findPans(new String(batch.encode(), StandardCharsets.UTF_8)))
                    .isEmpty();
        }
    }

    @Test
    void noCardNumberReachesTheSpool() throws IOException {
        Site site = buildSite(new MemoryTransport());
        site.open();
        site.collect();
        site.spool().sealAll();
        for (Path path : segments()) {
            assertThat(Pans.findPans(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)))
                    .isEmpty();
        }
    }

    @Test
    void noCardNumberReachesTheArchive() {
        Site site = buildSite(new MemoryTransport());
        site.open();
        site.collect();
        List<StoredRecord> rows = site.archive()
                .orElseThrow()
                .between(NanoTime.parse("2000-01-01T00:00:00Z"), NanoTime.parse("2100-01-01T00:00:00Z"), 100_000);
        for (StoredRecord row : rows) {
            assertThat(Pans.findPans(row.message())).isEmpty();
        }
    }

    @Test
    void somethingWasActuallyRedacted() {
        Site site = buildSite(new MemoryTransport());
        site.open();
        site.collect();
        assertThat(site.snapshot().get("redact_removed")).isPositive();
    }

    @Test
    void theArchiveHoldsEveryLine() throws IOException {
        Site site = shipped(new MemoryTransport());
        assertThat(site.snapshot())
                .containsEntry("archive_records", (long) expectedLines().size());
    }

    @Test
    void aRestartLosesNothingAndRepeatsNothing() throws IOException {
        MemoryTransport firstTransport = new MemoryTransport();
        Site first = buildSite(firstTransport);
        first.open();
        first.cycle();
        first.close();
        MemoryTransport secondTransport = new MemoryTransport();
        Site second = buildSite(secondTransport);
        second.open();
        second.cycle();
        second.close();
        second.ship();
        List<String> everything = new ArrayList<>(delivered(firstTransport));
        everything.addAll(delivered(secondTransport));
        assertThat(sorted(everything)).isEqualTo(sorted(expectedLines()));
    }

    @Test
    void newTrafficAfterARestartIsCollected() {
        Site first = buildSite(new MemoryTransport());
        first.open();
        first.cycle();
        first.close();
        new Simulation(202).writeCri(seeded.resolve("log"), 50);
        MemoryTransport transport = new MemoryTransport();
        Site second = buildSite(transport);
        second.open();
        second.cycle();
        second.close();
        second.ship();
        assertThat(delivered(transport)).isNotEmpty();
    }

    @Test
    void theSnapshotAddsUp() {
        Map<String, Long> found = shipped(new MemoryTransport()).snapshot();
        assertThat(found.get("ingest_records")).isEqualTo(found.get("archive_records"));
        assertThat(found.get("ship_records")).isLessThanOrEqualTo(found.get("ingest_records"));
        assertThat(found.get("ship_meter_used")).isPositive();
    }

    @Test
    void aStackTraceArrivesWhole() {
        MemoryTransport transport = new MemoryTransport();
        shipped(transport);
        assertThat(delivered(transport)).anyMatch(item -> item.contains("IllegalStateException"));
    }

    @Test
    void theDeliveredPayloadIsJson() {
        MemoryTransport transport = new MemoryTransport();
        shipped(transport);
        Map<String, Object> payload =
                Json.loadsObject(new String(transport.received().get(0).encode(), StandardCharsets.UTF_8));
        assertThat(payload).containsEntry("site", "US-TX-0417");
        assertThat((List<?>) payload.get("records")).isNotEmpty();
    }

    @Test
    void paymentRecordsShipBeforeChatter() {
        MemoryTransport transport = new MemoryTransport();
        Site site = buildSite(transport);
        site.open();
        site.collect();
        site.spool().sealAll();
        site.ship();
        List<String> sources = transport.received().stream().map(Batch::source).toList();
        List<Integer> payment = IntStream.range(0, sources.size())
                .filter(index -> sources.get(index).contains("payment"))
                .boxed()
                .toList();
        List<Integer> chatter = IntStream.range(0, sources.size())
                .filter(index -> sources.get(index).contains("chatter"))
                .boxed()
                .toList();
        assertThat(payment.isEmpty() || chatter.isEmpty() || payment.get(0) < chatter.get(0))
                .isTrue();
    }
}
