package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.ship.Compaction;
import io.github.rkaushikdev.trammelworks.ship.MemoryTransport;
import io.github.rkaushikdev.trammelworks.ship.Outcome;
import io.github.rkaushikdev.trammelworks.ship.Response;
import io.github.rkaushikdev.trammelworks.ship.ShipSettings;
import io.github.rkaushikdev.trammelworks.ship.Watermark;
import io.github.rkaushikdev.trammelworks.spool.SegmentReader;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteCompactionTest {

    static final String TS = "2025-03-04T12:00:00.000000000Z";

    @TempDir
    Path tmp;

    SiteTree tree;
    final List<Site> built = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tree = new SiteTree(tmp.resolve("log"));
    }

    @AfterEach
    void tearDown() {
        built.forEach(Site::release);
    }

    SiteConfig configFor(UnaryOperator<ShipSettings.Builder> ship) {
        return SiteFixtures.config(tree, tmp)
                .spool(SpoolSettings.builder()
                        .directory(PosixPath.asPosix(tmp.resolve("spool")))
                        .budgetBytes(4_000_000)
                        .segmentBytes(200_000)
                        .compress(false)
                        .build())
                .ship(ship.apply(ShipSettings.builder()
                                .monthlyAllowanceBytes(10_000_000)
                                .batchRecords(5))
                        .build())
                .build();
    }

    Site build(MemoryTransport transport) {
        return build(transport, UnaryOperator.identity());
    }

    Site build(MemoryTransport transport, UnaryOperator<ShipSettings.Builder> ship) {
        Site site = SiteFixtures.site(configFor(ship), transport);
        built.add(site);
        site.open();
        return site;
    }

    /** Acknowledges the first {@code after} batches, then stops answering. */
    static MemoryTransport stalling(int after) {
        List<Response> script = new ArrayList<>();
        for (int index = 0; index < after; index++) {
            script.add(new Response(Outcome.OK, 200));
        }
        return new MemoryTransport(script, new Response(Outcome.RETRYABLE, 503));
    }

    static void load(Site site, int count) {
        site.absorb(IntStream.range(0, count)
                .mapToObj(index -> new LogRecord(
                        "US-TX-0417",
                        "pump-bridge",
                        Stream.STDOUT,
                        NanoTime.parse(TS),
                        String.format("line %03d ", index) + "x".repeat(400)))
                .toList());
    }

    Path onlySegment() {
        List<Path> found = SiteFixtures.segments(tmp.resolve("spool"));
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    static List<String> messages(Path path) {
        return SegmentReader.readSegment(path).frames().stream()
                .map(frame -> String.valueOf(frame.payload().get("message")))
                .toList();
    }

    static long size(Path path) throws IOException {
        return Files.size(path);
    }

    @Test
    void aFullyAcknowledgedSegmentIsDeletedNotCompacted() {
        Site site = build(new MemoryTransport());
        load(site, 20);
        site.ship();
        assertThat(SiteFixtures.segments(tmp.resolve("spool"))).isEmpty();
        site.close();
    }

    @Test
    void aPartAcknowledgedSegmentShrinks() throws IOException {
        Site site = build(stalling(2), ship -> ship.compactMinReclaimBytes(1));
        load(site, 30);
        site.spool().sealAll();
        long before = size(onlySegment());
        site.ship();
        assertThat(size(onlySegment())).isLessThan(before);
        site.close();
    }

    @Test
    void whatIsLeftIsWhatWasNotAcknowledged() {
        Site site = build(stalling(2), ship -> ship.compactMinReclaimBytes(1));
        load(site, 30);
        site.spool().sealAll();
        site.ship();
        assertThat(messages(onlySegment()).get(0)).startsWith("line 010");
        site.close();
    }

    @Test
    void theWatermarkMatchesWhatIsOnDisk() {
        Site site = build(stalling(2), ship -> ship.compactMinReclaimBytes(1));
        load(site, 30);
        site.spool().sealAll();
        site.ship();
        Path segment = onlySegment();
        Watermark mark = site.shipper().acks().marks().get(segment.getFileName().toString());
        assertThat(mark.acked()).isZero();
        assertThat(mark.total())
                .isEqualTo(SegmentReader.readSegment(segment).frames().size());
        site.close();
    }

    @Test
    void theRestShipsOnTheNextPass() {
        MemoryTransport transport = stalling(2);
        Site site = build(transport, ship -> ship.compactMinReclaimBytes(1));
        load(site, 30);
        site.spool().sealAll();
        site.ship();
        transport.fallback(new Response(Outcome.OK, 200));
        site.shipper().backoff().succeed();
        site.ship();
        assertThat(SiteFixtures.segments(tmp.resolve("spool"))).isEmpty();
        site.close();
    }

    @Test
    void nothingIsLostAcrossACompaction() {
        MemoryTransport transport = stalling(2);
        Site site = build(transport, ship -> ship.compactMinReclaimBytes(1));
        load(site, 30);
        site.spool().sealAll();
        site.ship();
        transport.fallback(new Response(Outcome.OK, 200));
        site.shipper().backoff().succeed();
        site.ship();
        Set<String> sent = new HashSet<>(SiteFixtures.delivered(transport));
        assertThat(sent).hasSize(30);
        site.close();
    }

    @Test
    void compactionCanBeTurnedOff() throws IOException {
        Site site = build(stalling(2), ship -> ship.compact(false));
        load(site, 30);
        site.spool().sealAll();
        long before = size(onlySegment());
        site.ship();
        assertThat(size(onlySegment())).isEqualTo(before);
        site.close();
    }

    @Test
    void turningItOffGivesBackAnIdleResult() {
        Site site = build(new MemoryTransport(), ship -> ship.compact(false));
        assertThat(site.compact().idle()).isTrue();
        site.close();
    }

    @Test
    void aSavingUnderBothFloorsIsLeftAlone() throws IOException {
        // Both have to say no. Either one on its own is a reason to rewrite, which
        // is what lets a tiny segment be compacted on the fraction and a large one
        // on the byte count.
        Site site = build(
                stalling(2), ship -> ship.compactMinReclaimBytes(1024 * 1024).compactMinFraction(0.9));
        load(site, 30);
        site.spool().sealAll();
        long before = size(onlySegment());
        site.ship();
        assertThat(size(onlySegment())).isEqualTo(before);
        site.close();
    }

    @Test
    void openingClearsASidecarACrashLeft() throws IOException {
        Path spool = Files.createDirectories(tmp.resolve("spool"));
        Path stray = spool.resolve("000000000001-payment.spool" + Compaction.COMPACT_SUFFIX);
        Files.write(stray, "half written".getBytes(StandardCharsets.UTF_8));
        Site site = build(new MemoryTransport());
        assertThat(stray).doesNotExist();
        site.close();
    }

    @Test
    void theCountersSayWhatWasCompacted() {
        Site site = build(stalling(2), ship -> ship.compactMinReclaimBytes(1));
        load(site, 30);
        site.spool().sealAll();
        site.ship();
        assertThat(site.snapshot()).containsEntry("spool_compacted_segments", 1L);
        site.close();
    }

    @Test
    void aNegativeReclaimIsRefused() {
        assertThatThrownBy(
                        () -> ShipSettings.builder().compactMinReclaimBytes(-1).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void aFractionOutsideTheRangeIsRefused() {
        assertThatThrownBy(() -> ShipSettings.builder().compactMinFraction(1.5).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("fraction of a segment");
    }

    @Test
    void theCompactionKeysComeOutOfTheConfigBlock() {
        ShipSettings settings = ShipSettings.shipSettingsFromMapping(
                Maps.of("compact", false, "compact_min_reclaim", "2MiB", "compact_min_fraction", 0.5));
        assertThat(settings.compact()).isFalse();
        assertThat(settings.compactMinReclaimBytes()).isEqualTo(2L * 1024 * 1024);
        assertThat(settings.compactMinFraction()).isEqualTo(0.5);
    }

    @Test
    void anUnknownCompactionKeyIsRefused() {
        assertThatThrownBy(() -> ShipSettings.shipSettingsFromMapping(Map.of("compact_when", "tuesday")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown ship key");
    }

    @Test
    void aSavingOverTheFractionIsTakenEvenUnderTheByteFloor() throws IOException {
        Site site = build(stalling(2), ship -> ship.compactMinReclaimBytes(1024 * 1024));
        load(site, 30);
        site.spool().sealAll();
        long before = size(onlySegment());
        site.ship();
        assertThat(size(onlySegment())).isLessThan(before);
        site.close();
    }
}
