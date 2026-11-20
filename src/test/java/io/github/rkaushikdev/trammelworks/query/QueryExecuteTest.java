package io.github.rkaushikdev.trammelworks.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.QueryError;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueryExecuteTest {

    static final NanoTime NOW = NanoTime.parse("2025-03-04T12:00:00Z");
    static final PriorityTable TABLE = new PriorityTable(Map.of("payment-kernel", "payment", "price-sign", "chatter"));

    record Line(String unit, String when, String message, Map<String, String> labels, Stream stream) {}

    static final List<Line> CORPUS = List.of(
            new Line("payment-kernel", "11:00:00", "auth approved for lane 3", Map.of("lane", "3"), Stream.STDOUT),
            new Line("payment-kernel", "11:15:00", "auth declined for lane 4", Map.of("lane", "4"), Stream.STDERR),
            new Line("pump-bridge", "11:30:00", "nozzle 1 lifted", Map.of("lane", "1"), Stream.STDOUT),
            new Line("pump-bridge", "11:45:00", "nozzle 1 replaced", Map.of(), Stream.STDOUT),
            new Line("price-sign", "11:50:00", "heartbeat", Map.of(), Stream.STDOUT),
            new Line("tank-gauge", "11:55:00", "level 41200 ml", Map.of("tank", "2"), Stream.STDOUT));

    @TempDir
    Path tmp;

    ArchiveDb connection;
    Repository repo;

    @BeforeEach
    void setUp() {
        connection = Schema.openArchive(tmp.resolve("archive").resolve("records.sqlite3"));
        repo = new Repository(connection, TABLE);
        for (Line line : CORPUS) {
            repo.add(
                    new LogRecord(
                                    "US-TX-0417",
                                    line.unit(),
                                    line.stream(),
                                    NanoTime.parse("2025-03-04T" + line.when() + "Z"),
                                    line.message())
                            .replaceLabels(line.labels()),
                    NOW);
        }
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    static Query q(String text) {
        return Parser.parseQuery(text, NOW);
    }

    List<String> messages(String text) {
        return Execute.search(connection, q(text)).records().stream()
                .map(StoredRecord::message)
                .toList();
    }

    @Test
    void anEmptyFilterReturnsEverything() {
        assertThat(messages("")).hasSize(CORPUS.size());
    }

    @Test
    void theRecordsComeBackOldestFirst() {
        assertThat(messages("").get(0)).isEqualTo("auth approved for lane 3");
    }

    @Test
    void aUnitFilterNarrows() {
        assertThat(messages("unit = pump-bridge")).hasSize(2);
    }

    @Test
    void aClassFilterNarrows() {
        assertThat(messages("class = payment")).hasSize(2);
    }

    @Test
    void aContainsFilterNarrows() {
        assertThat(messages("message ~ declined")).containsExactly("auth declined for lane 4");
    }

    @Test
    void aContainsFilterIgnoresCase() {
        assertThat(messages("message ~ NOZZLE")).hasSize(2);
    }

    @Test
    void anInListNarrows() {
        assertThat(messages("unit in (pump-bridge, tank-gauge)")).hasSize(3);
    }

    @Test
    void aWindowNarrows() {
        assertThat(messages("since 2025-03-04T11:40:00Z")).hasSize(3);
    }

    @Test
    void twoWindowsBracketTheAnswer() {
        assertThat(messages("since 2025-03-04T11:20:00Z and until 2025-03-04T11:47:00Z"))
                .containsExactly("nozzle 1 lifted", "nozzle 1 replaced");
    }

    @Test
    void aLabelFilterIsAppliedAfterTheFetch() {
        SearchResult found = Execute.search(connection, q("label.lane = 3"));
        assertThat(found.records()).extracting(StoredRecord::message).containsExactly("auth approved for lane 3");
        assertThat(found.postFiltered()).isPositive();
    }

    @Test
    void aLabelUnderAnAndStillNarrowsInSql() {
        SearchResult found = Execute.search(connection, q("unit = payment-kernel and label.lane = 4"));
        assertThat(found.records()).extracting(StoredRecord::message).containsExactly("auth declined for lane 4");
        assertThat(found.scanned()).isEqualTo(2);
    }

    @Test
    void aLabelUnderAnOrStillGivesTheRightAnswer() {
        assertThat(messages("unit = price-sign or label.tank = 2"))
                .containsExactlyInAnyOrder("heartbeat", "level 41200 ml");
    }

    @Test
    void aLimitBoundsTheAnswer() {
        assertThat(messages("limit 2")).hasSize(2);
    }

    @Test
    void aLimitIsHonouredAfterAPostFilter() {
        assertThat(Execute.search(connection, q("not label.lane = 1 limit 2")).records())
                .hasSize(2);
    }

    @Test
    void aFilterMatchingNothingReturnsNothing() {
        assertThat(messages("unit = absent-unit")).isEmpty();
    }

    @Test
    void theScanCeilingTruncatesAndSaysSo() {
        SearchResult found = Execute.search(connection, q(""), 2);
        assertThat(found.truncated()).isTrue();
        assertThat(found.count()).isEqualTo(2);
    }

    @Test
    void aCeilingBelowOneIsRefused() {
        assertThatThrownBy(() -> Execute.search(connection, q(""), 0))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void theResultDescribesItself() {
        assertThat(Execute.search(connection, q("unit = pump-bridge")).describe())
                .isEqualTo("2 records from 2 scanned");
    }

    @Test
    void countingIgnoresTheLimit() {
        assertThat(Execute.countMatching(connection, q("limit 1"))).isEqualTo(CORPUS.size());
    }

    @Test
    void explainShowsAPushedFilter() {
        assertThat(Execute.explain(q("unit = a"))).isEqualTo("sql: unit = ?");
    }

    @Test
    void explainNamesWhatIsLeftInMemory() {
        assertThat(Execute.explain(q("unit = a and label.lane = 3"))).contains("then in memory: label.lane = 3");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "unit = pump-bridge",
                "class = payment",
                "message ~ nozzle",
                "message !~ nozzle",
                "unit in (pump-bridge, tank-gauge)",
                "stream = stderr",
                "since 2025-03-04T11:40:00Z",
                "until 2025-03-04T11:20:00Z",
                "unit = pump-bridge and message ~ lifted",
                "unit = pump-bridge or class = payment",
                "not unit = pump-bridge",
                "label.lane = 3",
                "label.lane = 3 or unit = price-sign",
                "unit = payment-kernel and not label.lane = 3",
                "(unit = pump-bridge or unit = tank-gauge) and not message ~ level"
            })
    void thePlannerAndTheEvaluatorAgree(String text) {
        // The differential test: SQL must say exactly what the tree says.
        Query query = q(text);
        List<String> throughSql = Execute.search(connection, query).records().stream()
                .map(StoredRecord::message)
                .toList();
        List<StoredRecord> everything =
                repo.between(NanoTime.parse("2025-03-04T00:00:00Z"), NanoTime.parse("2025-03-05T00:00:00Z"));
        List<String> inMemory = Evaluate.run(query, everything).stream()
                .map(StoredRecord::message)
                .toList();
        assertThat(throughSql).isEqualTo(inMemory);
    }

    @Test
    void aQueryOnAClosedArchiveIsRefused() {
        connection.close();
        assertThatThrownBy(() -> Execute.search(connection, q("")))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("refused the query");
    }
}
