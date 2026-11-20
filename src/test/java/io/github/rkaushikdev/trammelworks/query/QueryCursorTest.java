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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueryCursorTest {

    static final NanoTime NOW = NanoTime.parse("2025-06-10T12:00:00Z");

    @TempDir
    Path tmp;

    ArchiveDb connection;
    Repository repo;

    @BeforeEach
    void setUp() {
        connection = Schema.openArchive(tmp.resolve("archive").resolve("records.sqlite3"));
        repo = new Repository(connection);
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    void add(int index, long atNs) {
        LogRecord record = new LogRecord(
                "US-TX-0417", "pump-bridge", Stream.STDOUT, new NanoTime(atNs), String.format("line %03d", index));
        repo.add(index % 2 == 0 ? record.replaceLabels(Map.of("lane", "2")) : record, NOW);
    }

    void fill(int count) {
        for (int index = 0; index < count; index++) {
            add(index, NOW.epochNs() + index * 1000L);
        }
    }

    static Query q(String text) {
        return Parser.parseQuery(text, NOW);
    }

    static List<String> messages(SearchResult result) {
        return result.records().stream().map(StoredRecord::message).toList();
    }

    static List<String> lines(int from, int to, int step) {
        return IntStream.iterate(from, index -> index < to, index -> index + step)
                .mapToObj(index -> String.format("line %03d", index))
                .toList();
    }

    /** Walk every page, following the cursor until none is offered. */
    List<List<String>> pages(String text, int size) {
        List<List<String>> out = new ArrayList<>();
        Optional<Cursor> cursor = Optional.empty();
        for (int round = 0; round < 50; round++) {
            SearchResult found = Execute.search(
                    connection, q((text + " limit " + size).strip()), Execute.DEFAULT_SCAN_CEILING, cursor);
            if (found.records().isEmpty()) {
                break;
            }
            out.add(messages(found));
            cursor = found.nextCursor();
            if (cursor.isEmpty()) {
                break;
            }
        }
        return out;
    }

    List<String> walked(String text, int size) {
        return pages(text, size).stream().flatMap(List::stream).toList();
    }

    SearchResult page(String text, Optional<Cursor> after) {
        return Execute.search(connection, q(text), Execute.DEFAULT_SCAN_CEILING, after);
    }

    @Test
    void aCursorRoundTrips() {
        Cursor original = new Cursor(1749556800000000000L, 42);
        assertThat(Cursor.parseCursor(original.encode())).isEqualTo(original);
    }

    @Test
    void aCursorBeforeTheEpochRoundTrips() {
        Cursor original = new Cursor(-500, 1);
        assertThat(Cursor.parseCursor(original.encode())).isEqualTo(original);
    }

    @Test
    void aCursorReadsAsItsEncoding() {
        assertThat(new Cursor(7, 3)).hasToString("7.3");
    }

    @Test
    void aCursorKnowsItsInstant() {
        assertThat(new Cursor(1_000_000_000, 1).at().epochNs()).isEqualTo(1_000_000_000L);
    }

    @Test
    void aNegativeRowIsRefused() {
        assertThatThrownBy(() -> new Cursor(1, -1))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("cannot be negative");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "nonsense", "1", "1.", ".1", "a.b", "1.2.3", "1,2"})
    void aMalformedCursorIsRefused(String raw) {
        assertThatThrownBy(() -> Cursor.parseCursor(raw))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void surroundingSpaceIsForgiven() {
        assertThat(Cursor.parseCursor("  12.3  ")).isEqualTo(new Cursor(12, 3));
    }

    @Test
    void thePredicateBreaksTiesOnTheRow() {
        String sql = Cursor.cursorSql("at");
        assertThat(sql).contains("at > ?").contains("at = ? AND id > ?");
    }

    @Test
    void thePredicateTakesThreeValues() {
        assertThat(Cursor.cursorParams(new Cursor(9, 4))).containsExactly(9L, 9L, 4L);
    }

    @Test
    void aPlanWithoutACursorIsUnchanged() {
        Plan built = Plan.plan(q("unit = pump-bridge"));
        assertThat(built.fullWhere()).isEqualTo(built.whereSql());
        assertThat(built.allParams()).isEqualTo(built.params());
    }

    @Test
    void aPlanWithACursorCarriesIt() {
        Plan built = Plan.plan(q("unit = pump-bridge"), Optional.of(new Cursor(5, 2)));
        assertThat(built.fullWhere()).contains("id > ?");
        assertThat(built.allParams()).containsExactly("pump-bridge", 5L, 5L, 2L);
    }

    @Test
    void theCursorIsInTheStatement() {
        assertThat(Plan.plan(q(""), Optional.of(new Cursor(5, 2))).sql()).contains("id > ?");
    }

    @Test
    void aFirstPageOffersACursor() {
        fill(10);
        SearchResult found = page("limit 4", Optional.empty());
        assertThat(found.count()).isEqualTo(4);
        assertThat(found.more()).isTrue();
    }

    @Test
    void aSecondPageCarriesOn() {
        fill(10);
        SearchResult first = page("limit 4", Optional.empty());
        SearchResult second = page("limit 4", first.nextCursor());
        assertThat(messages(second)).containsExactly("line 004", "line 005", "line 006", "line 007");
    }

    @Test
    void theLastPageOffersNoCursor() {
        fill(6);
        SearchResult first = page("limit 4", Optional.empty());
        SearchResult second = page("limit 4", first.nextCursor());
        assertThat(second.count()).isEqualTo(2);
        assertThat(second.more()).isFalse();
    }

    @Test
    void pagingSeesEveryRecordOnce() {
        fill(25);
        assertThat(walked("", 4)).isEqualTo(lines(0, 25, 1));
    }

    @Test
    void pagingRepeatsNothing() {
        fill(25);
        List<String> walked = walked("", 4);
        assertThat(new HashSet<>(walked)).hasSameSizeAs(walked);
    }

    @Test
    void recordsSharingAnInstantAreNotSkipped() {
        // A container writing a burst gets one runtime timestamp for several lines.
        for (int index = 0; index < 9; index++) {
            add(index, NOW.epochNs());
        }
        assertThat(walked("", 2)).isEqualTo(lines(0, 9, 1));
    }

    @Test
    void recordsSharingAnInstantAreNotRepeated() {
        for (int index = 0; index < 9; index++) {
            add(index, NOW.epochNs());
        }
        List<String> walked = walked("", 2);
        assertThat(new HashSet<>(walked)).hasSameSizeAs(walked);
    }

    @Test
    void aRecordInsertedBehindTheReaderDoesNotShiftThePage() {
        // The collector is writing while the tool pages. An offset would slide.
        fill(10);
        SearchResult first = page("limit 4", Optional.empty());
        add(900, NOW.epochNs() - 5000);
        SearchResult second = page("limit 4", first.nextCursor());
        assertThat(messages(second).get(0)).isEqualTo("line 004");
    }

    @Test
    void pagingWorksWithAFilter() {
        fill(20);
        assertThat(walked("message ~ \"line 01\"", 3)).isEqualTo(lines(10, 20, 1));
    }

    @Test
    void pagingWorksWhenAResidualDropsRows() {
        fill(20);
        assertThat(walked("label.lane = 2", 3)).isEqualTo(lines(0, 20, 2));
    }

    @Test
    void theCursorPointsAtARecordThatWasReturned() {
        // Not at the last row read. The residual dropped rows nobody saw, and
        // resuming past them would skip whatever came next.
        fill(20);
        SearchResult found = page("label.lane = 2 limit 3", Optional.empty());
        StoredRecord last = found.records().get(found.records().size() - 1);
        assertThat(found.nextCursor()).hasValueSatisfying(cursor -> assertThat(cursor.atNs())
                .isEqualTo(last.bestAt().epochNs()));
    }

    @Test
    void aCursorPastTheEndReturnsNothing() {
        fill(5);
        Cursor far = new Cursor(NOW.epochNs() + 10_000_000, 0);
        assertThat(page("", Optional.of(far)).records()).isEmpty();
    }

    @Test
    void aSearchWithNoRecordsOffersNoCursor() {
        assertThat(page("", Optional.empty()).more()).isFalse();
    }
}
