package io.github.rkaushikdev.trammelworks.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.StanchionError;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveBatchTest {

    static final NanoTime NOW = NanoTime.parse("2025-06-10T12:00:00Z");

    @TempDir
    Path tmp;

    ArchiveDb connection;
    Repository repo;

    @BeforeEach
    void setUp() {
        connection = Schema.openArchive(ArchiveFixtures.archivePath(tmp));
        repo = new Repository(connection, ArchiveFixtures.TABLE);
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    static LogRecord record(int index) {
        return record(index, "pump-bridge");
    }

    static LogRecord record(int index, String unit) {
        return ArchiveFixtures.record(unit, NOW.shifted(index), String.format("line %05d", index));
    }

    static List<LogRecord> many(int count) {
        return many(count, "pump-bridge");
    }

    static List<LogRecord> many(int count, String unit) {
        return IntStream.range(0, count).mapToObj(index -> record(index, unit)).toList();
    }

    static <T> List<List<T>> chunks(List<T> items, int size) {
        List<List<T>> out = new ArrayList<>();
        Repository.chunks(items.iterator(), size).forEachRemaining(out::add);
        return out;
    }

    @Test
    void chunksOfAnEmptyRunAreNone() {
        assertThat(chunks(List.of(), 10)).isEmpty();
    }

    @Test
    void chunksComeOutTheSizeAskedFor() {
        assertThat(chunks(many(10), 4)).extracting(List::size).containsExactly(4, 4, 2);
    }

    @Test
    void aShortRunIsOneChunk() {
        assertThat(chunks(many(3), 500)).hasSize(1);
    }

    @Test
    void chunksKeepTheOrder() {
        List<LogRecord> found =
                chunks(many(7), 3).stream().flatMap(List::stream).toList();
        assertThat(found)
                .extracting(LogRecord::message)
                .isEqualTo(many(7).stream().map(LogRecord::message).toList());
    }

    @Test
    void chunksDoNotPullTheWholeRunIn() {
        // A backfill hands this an iterator over a day of records.
        AtomicInteger pulled = new AtomicInteger();
        Iterator<LogRecord> source =
                many(20).stream().peek(item -> pulled.incrementAndGet()).iterator();
        Repository.chunks(source, 5).next();
        assertThat(pulled.get()).isEqualTo(5);
    }

    @Test
    void aBatchOfNothingIsRefused() {
        assertThatThrownBy(() -> new Repository(connection, ArchiveFixtures.TABLE, 0))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void theDefaultBatchIsTheDocumentedOne() {
        assertThat(Repository.DEFAULT_BATCH).isEqualTo(500);
    }

    @Test
    void oneRecordStillStores() {
        assertThat(repo.add(record(1), NOW)).isTrue();
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void theSameRecordTwiceIsNotNew() {
        repo.add(record(1), NOW);
        assertThat(repo.add(record(1), NOW)).isFalse();
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void manyRecordsAllLand() {
        assertThat(repo.addAll(many(1200), NOW)).isEqualTo(1200);
        assertThat(repo.count()).isEqualTo(1200);
    }

    @Test
    void manyRecordsLandAcrossSeveralChunks() {
        Repository small = new Repository(connection, ArchiveFixtures.TABLE, 7);
        assertThat(small.addAll(many(50), NOW)).isEqualTo(50);
        assertThat(small.count()).isEqualTo(50);
    }

    @Test
    void replayingABatchAddsNothing() {
        repo.addAll(many(300), NOW);
        assertThat(repo.addAll(many(300), NOW)).isZero();
        assertThat(repo.count()).isEqualTo(300);
    }

    @Test
    void aHalfOverlappingBatchAddsOnlyTheNewPart() {
        repo.addAll(many(200), NOW);
        List<LogRecord> again =
                IntStream.range(100, 300).mapToObj(ArchiveBatchTest::record).toList();
        assertThat(repo.addAll(again, NOW)).isEqualTo(100);
        assertThat(repo.count()).isEqualTo(300);
    }

    @Test
    void theCountersAddUp() {
        repo.addAll(many(100), NOW);
        repo.addAll(many(100), NOW);
        assertThat(repo.written()).isEqualTo(100);
        assertThat(repo.duplicates()).isEqualTo(100);
    }

    @Test
    void aDuplicateInsideOneBatchIsCountedOnce() {
        List<LogRecord> batch = new ArrayList<>(many(5));
        batch.addAll(many(5));
        assertThat(repo.addAll(batch, NOW)).isEqualTo(5);
        assertThat(repo.duplicates()).isEqualTo(5);
    }

    @Test
    void theRecordsKeepTheirClass() {
        repo.addAll(many(10, "payment-kernel"), NOW);
        repo.addAll(many(10, "price-sign"), NOW);
        assertThat(repo.countsByClass()).isEqualTo(Map.of("payment", 10L, "chatter", 10L));
    }

    @Test
    void theRecordsKeepTheirContent() {
        repo.addAll(many(5), NOW);
        assertThat(repo.between(new NanoTime(0), NOW.shifted(1000)))
                .extracting(StoredRecord::message)
                .containsExactly("line 00000", "line 00001", "line 00002", "line 00003", "line 00004");
    }

    @Test
    void aBatchFromAGeneratorStores() {
        Iterable<LogRecord> generated = () -> many(40).iterator();
        assertThat(repo.addAll(generated, NOW)).isEqualTo(40);
        assertThat(repo.count()).isEqualTo(40);
    }

    @Test
    void anEmptyBatchStoresNothing() {
        assertThat(repo.addAll(List.of(), NOW)).isZero();
        assertThat(repo.written()).isZero();
        assertThat(repo.duplicates()).isZero();
    }

    @Test
    void aBatchOpensFarFewerTransactionsThanItHasRecords() {
        // The whole point: a transaction per record is what this replaced.
        Repository batched = new Repository(connection, ArchiveFixtures.TABLE, 100);
        int before = connection.transactions();
        batched.addAll(many(1000), NOW);
        assertThat(batched.count()).isEqualTo(1000);
        assertThat(connection.transactions() - before).isEqualTo(10);
    }

    @Test
    void oneRecordOpensOneTransaction() {
        int before = connection.transactions();
        repo.add(record(1), NOW);
        assertThat(connection.transactions() - before).isEqualTo(1);
    }
}
