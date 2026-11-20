package io.github.rkaushikdev.trammelworks.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.StanchionError;
import io.github.rkaushikdev.trammelworks.Stream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArchiveRepositoryTest {

    static final NanoTime NOW = NanoTime.parse("2025-03-04T12:00:00Z");
    static final NanoTime DAY_START = at("2025-03-04T00:00:00Z");
    static final NanoTime DAY_END = at("2025-03-05T00:00:00Z");

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

    static NanoTime at(String raw) {
        return NanoTime.parse(raw);
    }

    static LogRecord record() {
        return ArchiveFixtures.record("pump-bridge", at("2025-03-04T12:00:00Z"), "nozzle 1 lifted");
    }

    List<StoredRecord> today() {
        return repo.between(DAY_START, DAY_END);
    }

    @Test
    void aRecordIsStored() {
        assertThat(repo.add(record(), NOW)).isTrue();
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void theSameRecordTwiceIsStoredOnce() {
        repo.add(record(), NOW);
        assertThat(repo.add(record(), NOW)).isFalse();
        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.duplicates()).isEqualTo(1);
    }

    @Test
    void twoDifferentRecordsAreBothStored() {
        repo.add(record().withMessage("one"), NOW);
        repo.add(record().withMessage("two"), NOW);
        assertThat(repo.count()).isEqualTo(2);
    }

    @Test
    void theClassComesFromThePriorityTable() {
        repo.add(record().withUnit("payment-kernel"), NOW);
        assertThat(repo.countsByClass()).isEqualTo(Map.of("payment", 1L));
    }

    @Test
    void anUnassignedUnitLandsInTheDefaultClass() {
        repo.add(record(), NOW);
        assertThat(repo.countsByClass()).isEqualTo(Map.of("standard", 1L));
    }

    @Test
    void aRecordReadsBackWhole() {
        repo.add(record().replaceLabels(Map.of("lane", "3")), NOW);
        List<StoredRecord> found = today();
        assertThat(found).hasSize(1);
        assertThat(found.get(0).message()).isEqualTo("nozzle 1 lifted");
        assertThat(found.get(0).labels()).isEqualTo(Map.of("lane", "3"));
        assertThat(found.get(0).unit()).isEqualTo("pump-bridge");
    }

    @Test
    void aStoredRecordCanBecomeALogRecordAgain() {
        repo.add(record().replaceLabels(Map.of("lane", "3")), NOW);
        LogRecord rebuilt = today().get(0).toRecord();
        assertThat(rebuilt.site()).isEqualTo(ArchiveFixtures.SITE);
        assertThat(rebuilt.labels()).isEqualTo(Map.of("lane", "3"));
    }

    @Test
    void theNanosecondPrecisionSurvives() {
        repo.add(record().withObservedAt(at("2025-03-04T12:00:00.000000042Z")), NOW);
        assertThat(today().get(0).observedAt().nanosInSecond()).isEqualTo(42);
    }

    @Test
    void aCorrectedTimeIsKeptBesideTheRawOne() {
        repo.add(
                record().withObservedAt(at("2025-03-04T11:59:53Z"))
                        .withCorrection(Optional.of(at("2025-03-04T12:00:00Z")), "interpolated"),
                NOW);
        StoredRecord found = today().get(0);
        assertThat(found.observedAt()).isEqualTo(at("2025-03-04T11:59:53Z"));
        assertThat(found.correctedAt()).contains(at("2025-03-04T12:00:00Z"));
        assertThat(found.confidence()).isEqualTo("interpolated");
    }

    @Test
    void theWindowIsReadAgainstTheCorrectedTime() {
        repo.add(record().withObservedAt(at("2025-03-04T10:00:00Z")).withCorrectedAt(at("2025-03-04T12:00:00Z")), NOW);
        assertThat(repo.between(at("2025-03-04T11:00:00Z"), at("2025-03-04T13:00:00Z")))
                .hasSize(1);
        assertThat(repo.between(at("2025-03-04T09:00:00Z"), at("2025-03-04T11:00:00Z")))
                .isEmpty();
    }

    @Test
    void recordsComeBackOldestFirst() {
        for (int hour : new int[] {14, 12, 13}) {
            repo.add(record().withObservedAt(at("2025-03-04T" + hour + ":00:00Z")), NOW);
        }
        assertThat(today())
                .extracting(item -> item.observedAt().toDateTime().getHour())
                .containsExactly(12, 13, 14);
    }

    @Test
    void theWindowBoundariesAreInclusive() {
        repo.add(record(), NOW);
        assertThat(repo.between(at("2025-03-04T12:00:00Z"), at("2025-03-04T12:00:00Z")))
                .hasSize(1);
    }

    @Test
    void aBackwardsWindowIsRefused() {
        assertThatThrownBy(() -> repo.between(DAY_END, DAY_START))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("ends before it starts");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 200_000})
    void anOutOfRangeLimitIsRefused(int limit) {
        assertThatThrownBy(() -> repo.between(DAY_START, DAY_END, limit))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("limit is out of range");
    }

    @Test
    void aNegativeOffsetIsRefused() {
        assertThatThrownBy(() -> repo.between(DAY_START, DAY_END, null, null, Repository.DEFAULT_LIMIT, -1))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("offset cannot be negative");
    }

    @Test
    void theLimitBoundsTheAnswer() {
        for (int index = 0; index < 5; index++) {
            repo.add(record().withMessage("line " + index), NOW);
        }
        assertThat(repo.between(DAY_START, DAY_END, 2)).hasSize(2);
    }

    @Test
    void pagingWalksTheWholeWindow() {
        for (int index = 0; index < 5; index++) {
            repo.add(record().withObservedAt(at("2025-03-04T12:00:0" + index + "Z")), NOW);
        }
        Set<String> first = repo.between(DAY_START, DAY_END, 2).stream()
                .map(StoredRecord::key)
                .collect(Collectors.toSet());
        Set<String> second = repo.between(DAY_START, DAY_END, null, null, 2, 2).stream()
                .map(StoredRecord::key)
                .collect(Collectors.toSet());
        Set<String> both = new HashSet<>(first);
        both.retainAll(second);
        assertThat(both).isEmpty();
    }

    @Test
    void filteringByUnit() {
        repo.add(record(), NOW);
        repo.add(record().withUnit("tank-gauge"), NOW);
        assertThat(repo.between(DAY_START, DAY_END, "tank-gauge", null, Repository.DEFAULT_LIMIT, 0))
                .extracting(StoredRecord::unit)
                .containsExactly("tank-gauge");
    }

    @Test
    void filteringByClass() {
        repo.add(record().withUnit("payment-kernel"), NOW);
        repo.add(record().withUnit("price-sign"), NOW);
        assertThat(repo.between(DAY_START, DAY_END, null, "payment", Repository.DEFAULT_LIMIT, 0))
                .extracting(StoredRecord::klass)
                .containsExactly("payment");
    }

    @Test
    void theUnitsAreListed() {
        repo.add(record().withUnit("tank-gauge"), NOW);
        repo.add(record(), NOW);
        assertThat(repo.units()).containsExactly("pump-bridge", "tank-gauge");
    }

    @Test
    void anEmptyArchiveHasNoSpan() {
        assertThat(repo.span()).isEmpty();
    }

    @Test
    void theSpanCoversTheRecords() {
        repo.add(record().withObservedAt(at("2025-03-04T10:00:00Z")), NOW);
        repo.add(record().withObservedAt(at("2025-03-04T14:00:00Z")), NOW);
        Repository.Span span = repo.span().orElseThrow();
        assertThat(span.oldest()).isEqualTo(at("2025-03-04T10:00:00Z"));
        assertThat(span.newest()).isEqualTo(at("2025-03-04T14:00:00Z"));
    }

    @Test
    void addAllReportsHowManyWereNew() {
        List<LogRecord> batch = IntStream.range(0, 3)
                .mapToObj(index -> record().withMessage("line " + index))
                .toList();
        assertThat(repo.addAll(batch, NOW)).isEqualTo(3);
        assertThat(repo.addAll(batch, NOW)).isZero();
    }

    @Test
    void theWriteCounterFollows() {
        repo.add(record(), NOW);
        repo.add(record(), NOW);
        assertThat(repo.written()).isEqualTo(1);
        assertThat(repo.duplicates()).isEqualTo(1);
    }

    @Test
    void aRowWithBrokenLabelsReadsAsEmpty() {
        repo.add(record(), NOW);
        repo.db().execute("UPDATE records SET labels = 'not json'");
        assertThat(today().get(0).labels()).isEmpty();
    }

    @Test
    void bestAtPrefersTheCorrection() {
        StoredRecord stored = new StoredRecord(
                "k",
                ArchiveFixtures.SITE,
                "pump-bridge",
                "standard",
                Stream.STDOUT,
                at("2025-03-04T11:00:00Z"),
                Optional.of(at("2025-03-04T12:00:00Z")),
                "measured",
                "x",
                Map.of(),
                NOW);
        assertThat(stored.bestAt()).isEqualTo(at("2025-03-04T12:00:00Z"));
    }
}
