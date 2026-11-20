package io.github.rkaushikdev.trammelworks.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.IngestConfig;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.PosixPath;
import io.github.rkaushikdev.trammelworks.Site;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.ship.ShipSettings;
import io.github.rkaushikdev.trammelworks.spool.Budget;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import io.github.rkaushikdev.trammelworks.spool.Spool;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceMetricsTest {

    static final NanoTime NOW = NanoTime.parse("2025-06-10T12:00:00Z");
    static final PriorityTable TABLE = new PriorityTable(Map.of("payment-kernel", "payment", "price-sign", "chatter"));

    @TempDir
    Path tmp;

    SiteConfig config;

    @BeforeEach
    void setUp() {
        config = configFor(UnaryOperator.identity());
    }

    SiteConfig configFor(UnaryOperator<SiteConfig.Builder> over) {
        return over.apply(SiteConfig.builder("US-TX-0417")
                        .ingest(IngestConfig.at(PosixPath.asPosix(tmp.resolve("containers"))))
                        .spool(SpoolSettings.builder()
                                .directory(PosixPath.asPosix(tmp.resolve("spool")))
                                .budgetBytes(1_000_000)
                                .segmentBytes(4096)
                                .priority(TABLE)
                                .build())
                        .ship(ShipSettings.builder()
                                .monthlyAllowanceBytes(10_000_000)
                                .build())
                        .archive(ArchiveSettings.builder()
                                .path(PosixPath.asPosix(tmp.resolve("archive").resolve("records.sqlite3")))
                                .maxBytes(500_000)
                                .build()))
                .build();
    }

    static LogRecord record(int index, String unit) {
        return new LogRecord(
                "US-TX-0417",
                unit,
                Stream.STDOUT,
                new NanoTime(NOW.epochNs() + index),
                "line " + index + " " + "x".repeat(200));
    }

    void fillSpool(int count) {
        fillSpool(count, "pump-bridge");
    }

    void fillSpool(int count, String unit) {
        Spool spool = Spool.builder(
                        config.spool().path(), new Budget(config.spool().budgetBytes()))
                .table(TABLE)
                .segmentBytes(config.spool().segmentBytes())
                .compress(false)
                .build();
        for (int index = 0; index < count; index++) {
            spool.append(record(index, unit));
        }
        spool.sealAll();
    }

    void fillArchive(int count) {
        try (ArchiveDb connection = Schema.openArchive(config.archive().file())) {
            new Repository(connection, TABLE)
                    .addAll(
                            IntStream.range(0, count)
                                    .mapToObj(index -> record(index, "pump-bridge"))
                                    .toList(),
                            NOW);
        }
    }

    Path state() {
        return Site.stateDirectory(config.spool().directory());
    }

    Service serviceFor() {
        return new Service(config, NOW, "");
    }

    Map<String, Object> metricsPayload() {
        return Json.loadsObject(
                new String(serviceFor().handle(ServiceRequest.of("/metrics")).body(), StandardCharsets.UTF_8));
    }

    @Test
    void anEmptyGaugeSetRendersNothing() {
        assertThat(new Gauges().render()).isEmpty();
    }

    @Test
    void gaugesRenderOnePairPerLine() {
        Gauges found = new Gauges();
        found.set("b_second", 2);
        found.set("a_first", 1);
        assertThat(found.render()).isEqualTo("a_first 1\nb_second 2\n");
    }

    @Test
    void gaugesComeBackSorted() {
        Gauges found = new Gauges();
        found.set("zeta", 1);
        found.set("alpha", 2);
        assertThat(found.asDict().keySet()).containsExactly("alpha", "zeta");
    }

    @Test
    void aMissingAreaIsNotedNotRaised() {
        Gauges found = new Gauges();
        found.noteMissing("archive");
        assertThat(found.missing()).containsExactly("archive");
    }

    @Test
    void theSpoolGaugesReportAnEmptySpool() {
        Gauges found = new Gauges();
        Metrics.spoolGauges(config, found);
        assertThat(found.values())
                .containsEntry("spool_used_bytes", 0L)
                .containsEntry("spool_segments", 0L)
                .containsEntry("spool_limit_bytes", 1_000_000L);
    }

    @Test
    void theSpoolGaugesFollowWhatWasWritten() {
        fillSpool(40);
        Gauges found = new Gauges();
        Metrics.spoolGauges(config, found);
        assertThat(found.values().get("spool_used_bytes")).isPositive();
        assertThat(found.values().get("spool_segments")).isPositive();
    }

    @Test
    void theSpoolGaugesSplitByClass() {
        fillSpool(20, "payment-kernel");
        Gauges found = new Gauges();
        Metrics.spoolGauges(config, found);
        assertThat(found.values().get("spool_class_bytes_payment")).isPositive();
        assertThat(found.values()).containsEntry("spool_class_bytes_chatter", 0L);
    }

    @Test
    void everyClassGetsAGaugeEvenWhenEmpty() {
        Gauges found = new Gauges();
        Metrics.spoolGauges(config, found);
        for (String klass : List.of("payment", "standard", "chatter")) {
            assertThat(found.values()).containsKey("spool_class_bytes_" + klass);
        }
    }

    @Test
    void theFreeBytesAndUsedBytesAddUp() {
        fillSpool(30);
        Gauges found = new Gauges();
        Metrics.spoolGauges(config, found);
        long total = found.values().get("spool_used_bytes") + found.values().get("spool_free_bytes");
        assertThat(total).isEqualTo(found.values().get("spool_limit_bytes"));
    }

    @Test
    void theShipGaugesReportAFreshMeter() {
        Gauges found = new Gauges();
        Metrics.shipGauges(config, state(), found);
        assertThat(found.values())
                .containsEntry("ship_allowance_bytes", 10_000_000L)
                .containsEntry("ship_used_bytes", 0L)
                .containsEntry("ship_remaining_bytes", 10_000_000L);
    }

    @Test
    void theShipGaugesReportNothingOutstanding() {
        Gauges found = new Gauges();
        Metrics.shipGauges(config, state(), found);
        assertThat(found.values())
                .containsEntry("ship_outstanding_records", 0L)
                .containsEntry("ship_pending_segments", 0L);
    }

    @Test
    void theArchiveGaugesSayWhenItIsOff() {
        SiteConfig off = configFor(builder ->
                builder.archive(ArchiveSettings.builder().enabled(false).build()));
        Gauges found = new Gauges();
        Metrics.archiveGauges(off, found);
        assertThat(found.values()).containsEntry("archive_enabled", 0L).doesNotContainKey("archive_records");
    }

    @Test
    void anArchiveThatHasNeverBeenWrittenIsMissingNotBroken() {
        Gauges found = new Gauges();
        Metrics.archiveGauges(config, found);
        assertThat(found.values()).containsEntry("archive_enabled", 1L);
        assertThat(found.missing()).containsExactly("archive");
    }

    @Test
    void theArchiveGaugesCountTheRecords() {
        fillArchive(25);
        Gauges found = new Gauges();
        Metrics.archiveGauges(config, found);
        assertThat(found.values()).containsEntry("archive_records", 25L).containsEntry("archive_limit_bytes", 500_000L);
        assertThat(found.values().get("archive_bytes")).isPositive();
    }

    @Test
    void gatheringCoversEveryArea() {
        fillSpool(10);
        fillArchive(10);
        Gauges found = Metrics.gather(config, state());
        assertThat(found.values().get("spool_segments")).isPositive();
        assertThat(found.values()).containsEntry("archive_records", 10L);
        assertThat(found.values().get("ship_allowance_bytes")).isPositive();
    }

    @Test
    void gatheringReportsHowManyAreasWouldNotRead() {
        assertThat(Metrics.gather(config, state()).values()).containsEntry("site_areas_unreadable", 1L);
    }

    @Test
    void oneUnreadableAreaDoesNotStopTheOthers() {
        // The archive has never been written. The spool still answers.
        assertThat(Metrics.gather(config, state()).values()).containsKey("spool_used_bytes");
    }

    @Test
    void theMetricsRouteAnswers() {
        assertThat(serviceFor().handle(ServiceRequest.of("/metrics")).status()).isEqualTo(200);
    }

    @Test
    void theMetricsRouteCarriesTheSite() {
        assertThat(metricsPayload()).containsEntry("site", "US-TX-0417");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMetricsRouteCarriesTheGauges() {
        fillSpool(20);
        Map<String, Object> gauges = (Map<String, Object>) metricsPayload().get("gauges");
        assertThat((Long) gauges.get("spool_segments")).isPositive();
    }

    @Test
    void theMetricsRouteCarriesTheTextForm() {
        List<String> lines =
                ((String) metricsPayload().get("text")).strip().lines().toList();
        assertThat(lines).allMatch(line -> line.split(" ").length == 2);
        assertThat(lines).anyMatch(line -> line.startsWith("spool_limit_bytes "));
    }

    @Test
    void theTextFormAgreesWithTheGauges() {
        fillSpool(15);
        Map<String, Object> payload = metricsPayload();
        Map<String, Object> fromText = new LinkedHashMap<>();
        for (String line : ((String) payload.get("text")).strip().lines().toList()) {
            String[] pair = line.split(" ");
            fromText.put(pair[0], Long.parseLong(pair[1]));
        }
        assertThat(fromText).isEqualTo(payload.get("gauges"));
    }

    @Test
    void theMetricsRouteNamesWhatItCouldNotRead() {
        assertThat(metricsPayload().get("unreadable")).isEqualTo(List.of("archive"));
    }

    @Test
    void theMetricsRouteIsStillReadOnly() {
        assertThat(serviceFor()
                        .handle(ServiceRequest.of("/metrics", Map.of(), "POST"))
                        .status())
                .isEqualTo(405);
    }

    @Test
    void theMetricsRouteHonoursAToken() {
        assertThat(new Service(config, NOW, "hunter2")
                        .handle(ServiceRequest.of("/metrics"))
                        .status())
                .isEqualTo(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMetricsRouteIsListedWhenAPathIsWrong() {
        Map<String, Object> payload = Json.loadsObject(
                new String(serviceFor().handle(ServiceRequest.of("/nope")).body(), StandardCharsets.UTF_8));
        assertThat((List<Object>) payload.get("routes")).contains("/metrics");
    }
}
