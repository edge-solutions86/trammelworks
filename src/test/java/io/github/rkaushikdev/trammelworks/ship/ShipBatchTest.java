package io.github.rkaushikdev.trammelworks.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.ShipError;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ShipBatchTest {

    static final String SITE = "US-TX-0417";

    static Map<String, Object> record(int index) {
        return record(index, "nozzle 1 lifted");
    }

    static Map<String, Object> record(int index, String message) {
        return Maps.of("unit", "pump-bridge", "n", index, "message", message);
    }

    static List<Map<String, Object>> records(int count) {
        return IntStream.range(0, count).mapToObj(ShipBatchTest::record).toList();
    }

    static List<Batch> batches(int count) {
        return Batches.cut(SITE, records(count));
    }

    static List<Batch> batches(int count, BatchLimits limits) {
        return Batches.cut(SITE, records(count), limits);
    }

    @Test
    void anEmptyBatchIsRefused() {
        assertThatThrownBy(() -> new Batch(SITE, List.of()))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("cannot be empty");
    }

    @Test
    void oneRecordMakesOneBatch() {
        List<Batch> found = batches(1);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).count()).isEqualTo(1);
    }

    @Test
    void noRecordsMakeNoBatches() {
        assertThat(batches(0)).isEmpty();
    }

    @Test
    void theRecordsKeepTheirOrder() {
        List<Object> seen = batches(5, BatchLimits.records(2)).stream()
                .flatMap(batch -> batch.records().stream())
                .map(item -> item.get("n"))
                .toList();
        assertThat(seen).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void theRecordBoundCutsTheBatch() {
        assertThat(batches(5, BatchLimits.records(2))).extracting(Batch::count).containsExactly(2, 2, 1);
    }

    @Test
    void theByteBoundCutsTheBatch() {
        int one = Batches.recordSize(record(0));
        List<Batch> found = batches(4, BatchLimits.bytes(one * 2L + 1));
        assertThat(found).allMatch(batch -> batch.count() <= 2);
        assertThat(found.stream().mapToInt(Batch::count).sum()).isEqualTo(4);
    }

    @Test
    void aRecordLargerThanTheBoundStillGoesAlone() {
        BatchStats stats = new BatchStats();
        List<Batch> found = Batches.cut(SITE, List.of(record(0, "x".repeat(5000))), BatchLimits.bytes(100), "", stats);
        assertThat(found).hasSize(1);
        assertThat(stats.oversized()).isEqualTo(1);
    }

    @Test
    void anOversizedRecordDoesNotDragOthersWithIt() {
        List<Map<String, Object>> input = List.of(record(0), record(1, "x".repeat(5000)), record(2));
        List<Batch> found = Batches.cut(SITE, input, BatchLimits.bytes(200));
        assertThat(found.stream().mapToInt(Batch::count).sum()).isEqualTo(3);
        assertThat(found).anyMatch(batch -> batch.count() == 1);
    }

    @Test
    void theFirstIndexTracksPositionInTheStream() {
        List<Batch> found = batches(5, BatchLimits.records(2));
        assertThat(found).extracting(Batch::firstIndex).containsExactly(0, 2, 4);
        assertThat(found).extracting(Batch::lastIndex).containsExactly(1, 3, 4);
    }

    @Test
    void theBatchIdIsStableForTheSameRecords() {
        assertThat(batches(3).get(0).batchId()).isEqualTo(batches(3).get(0).batchId());
    }

    @Test
    void theBatchIdChangesWithTheRecords() {
        assertThat(new Batch(SITE, List.of(record(0))).batchId())
                .isNotEqualTo(new Batch(SITE, List.of(record(1))).batchId());
    }

    @Test
    void theBatchIdChangesWithTheSite() {
        assertThat(new Batch(SITE, List.of(record(0))).batchId())
                .isNotEqualTo(new Batch("GB-LDN-0009", List.of(record(0))).batchId());
    }

    @Test
    void theBatchIdChangesWithTheSource() {
        assertThat(new Batch(SITE, List.of(record(0)), "a.spool").batchId())
                .isNotEqualTo(new Batch(SITE, List.of(record(0)), "b.spool").batchId());
    }

    @Test
    void theBatchIdDoesNotDependOnKeyOrder() {
        Map<String, Object> left = new LinkedHashMap<>();
        left.put("a", 1);
        left.put("b", 2);
        Map<String, Object> right = new LinkedHashMap<>();
        right.put("b", 2);
        right.put("a", 1);
        assertThat(new Batch(SITE, List.of(left)).batchId()).isEqualTo(new Batch(SITE, List.of(right)).batchId());
    }

    @Test
    void theBatchIdSeparatesRecordsSoAShiftIsVisible() {
        Batch left = new Batch(SITE, List.of(Maps.of("m", "ab"), Maps.of("m", "c")));
        Batch right = new Batch(SITE, List.of(Maps.of("m", "a"), Maps.of("m", "bc")));
        assertThat(left.batchId()).isNotEqualTo(right.batchId());
    }

    @Test
    void theBatchIdIsHexOfTheDeclaredWidth() {
        String found = batches(1).get(0).batchId();
        assertThat(found).hasSize(24);
        assertThat(new BigInteger(found, 16)).isNotNegative();
    }

    @Test
    void theBatchIdMatchesTheDigestTheCollectorComputes() {
        // Pinned to the id the collector computes, so the site and the collector agree.
        assertThat(new Batch(SITE, List.of(record(0)), "000000000041-payment.spool").batchId())
                .isEqualTo(REFERENCE_ID);
    }

    static final String REFERENCE_ID = "ec7ffffde2f25608bd93ecd6";

    @Test
    void aRetryOfTheSameCutCarriesTheSameId() {
        assertThat(batches(4, BatchLimits.records(2)))
                .extracting(Batch::batchId)
                .isEqualTo(batches(4, BatchLimits.records(2)).stream()
                        .map(Batch::batchId)
                        .toList());
    }

    @Test
    void thePayloadCarriesTheIdAndCount() {
        Map<String, Object> payload = batches(2).get(0).payload();
        assertThat(payload.get("site")).isEqualTo(SITE);
        assertThat(payload.get("count")).isEqualTo(2);
        assertThat((List<?>) payload.get("records")).hasSize(2);
        assertThat((String) payload.get("batch_id")).isNotEmpty();
    }

    @Test
    void theEncodedFormIsJson() {
        Map<String, Object> decoded =
                Json.loadsObject(new String(batches(2).get(0).encode(), StandardCharsets.UTF_8));
        assertThat(decoded.get("count")).isEqualTo(2L);
    }

    @Test
    void theWireSizeMatchesTheEncodedLength() {
        Batch batch = batches(2).get(0);
        assertThat(batch.wireBytes()).isEqualTo(batch.encode().length);
    }

    @Test
    void recordSizeCountsTheSeparator() {
        assertThat(Batches.recordSize(Map.of("a", 1))).isEqualTo("{\"a\":1}".length() + 1);
    }

    @Test
    void theLimitsRejectANonPositiveBound() {
        assertThatThrownBy(() -> BatchLimits.bytes(0))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("max_bytes must be positive");
        assertThatThrownBy(() -> BatchLimits.records(0))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("max_records must be positive");
    }

    @Test
    void theStatsCountWhatWasCut() {
        BatchStats stats = new BatchStats();
        Batches.cut(SITE, records(5), BatchLimits.records(2), "", stats);
        assertThat(stats.batches()).isEqualTo(3);
        assertThat(stats.records()).isEqualTo(5);
        assertThat(stats.bytesPlanned()).isPositive();
    }

    @Test
    void theStatsRememberTheLargestBatch() {
        BatchStats stats = new BatchStats();
        Batches.cut(SITE, records(5), BatchLimits.records(2), "", stats);
        assertThat(stats.largest()).isPositive();
    }

    @Test
    void theSourceIsCarriedOntoThePayload() {
        List<Batch> found = Batches.cut(
                SITE, List.of(record(0)), new BatchLimits(), "000000000041-payment.spool", new BatchStats());
        assertThat(found.get(0).payload().get("source")).isEqualTo("000000000041-payment.spool");
    }
}
