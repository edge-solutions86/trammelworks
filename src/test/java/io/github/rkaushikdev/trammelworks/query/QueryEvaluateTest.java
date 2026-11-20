package io.github.rkaushikdev.trammelworks.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.QueryError;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class QueryEvaluateTest {

    static final NanoTime NOW = NanoTime.parse("2025-03-04T12:00:00Z");

    static NanoTime at(String raw) {
        return NanoTime.parse(raw);
    }

    static StoredRecord record() {
        return record("auth approved for lane 3");
    }

    static StoredRecord record(String message) {
        return record(message, at("2025-03-04T11:00:00Z"), Optional.empty());
    }

    static StoredRecord record(String message, NanoTime observed, Optional<NanoTime> corrected) {
        return new StoredRecord(
                "k",
                "US-TX-0417",
                "payment-kernel",
                "payment",
                Stream.STDOUT,
                observed,
                corrected,
                "measured",
                message,
                Map.of("lane", "3"),
                NOW);
    }

    static boolean check(String text) {
        return check(text, record());
    }

    static boolean check(String text, Object item) {
        return Evaluate.matches(Parser.parseQuery(text, NOW).where(), item);
    }

    /** A node no consumer knows how to read. */
    record Odd() implements Node {
        @Override
        public String render() {
            return "odd";
        }
    }

    @Test
    void anEmptyFilterMatches() {
        assertThat(Evaluate.matches(Optional.empty(), record())).isTrue();
    }

    @Test
    void anEqualityMatches() {
        assertThat(check("unit = payment-kernel")).isTrue();
        assertThat(check("unit = tank-gauge")).isFalse();
    }

    @Test
    void anInequalityMatches() {
        assertThat(check("unit != tank-gauge")).isTrue();
        assertThat(check("unit != payment-kernel")).isFalse();
    }

    @Test
    void aContainsMatchIgnoresCase() {
        assertThat(check("message ~ APPROVED")).isTrue();
        assertThat(check("message ~ declined")).isFalse();
    }

    @Test
    void aNegatedContainsMatch() {
        assertThat(check("message !~ declined")).isTrue();
        assertThat(check("message !~ approved")).isFalse();
    }

    @Test
    void orderingOperatorsCompareAsStrings() {
        assertThat(Evaluate.compareValues("b", Op.GT, "a")).isTrue();
        assertThat(Evaluate.compareValues("a", Op.LT, "b")).isTrue();
        assertThat(Evaluate.compareValues("a", Op.LE, "a")).isTrue();
        assertThat(Evaluate.compareValues("a", Op.GE, "a")).isTrue();
    }

    @Test
    void anUnknownOperatorIsRefused() {
        assertThatThrownBy(() -> Evaluate.compareValues("a", null, "b"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("unknown operator");
    }

    @Test
    void theClassFieldReadsTheClass() {
        assertThat(check("class = payment")).isTrue();
        assertThat(check("class = chatter")).isFalse();
    }

    @Test
    void theStreamFieldReadsTheStream() {
        assertThat(check("stream = stdout")).isTrue();
        assertThat(check("stream = stderr")).isFalse();
    }

    @Test
    void theConfidenceFieldIsReadable() {
        assertThat(check("confidence = measured")).isTrue();
    }

    @Test
    void aLabelIsReadable() {
        assertThat(check("label.lane = 3")).isTrue();
        assertThat(check("label.lane = 4")).isFalse();
    }

    @Test
    void anAbsentLabelDoesNotMatchAnEquality() {
        assertThat(check("label.pump = 7")).isFalse();
    }

    @Test
    void anAbsentLabelDoesMatchANegation() {
        assertThat(check("label.pump != 7")).isTrue();
        assertThat(check("label.pump !~ 7")).isTrue();
    }

    @Test
    void fieldValueReadsWhatIsThere() {
        assertThat(Evaluate.fieldValue(record(), "unit")).hasValue("payment-kernel");
        assertThat(Evaluate.fieldValue(record(), "label.lane")).hasValue("3");
        assertThat(Evaluate.fieldValue(record(), "label.absent")).isEmpty();
    }

    @Test
    void anInListMatchesAnyMember() {
        assertThat(check("unit in (payment-kernel, tank-gauge)")).isTrue();
        assertThat(check("unit in (tank-gauge, price-sign)")).isFalse();
    }

    @Test
    void anInListOnAnAbsentLabelDoesNotMatch() {
        assertThat(check("label.pump in (7)")).isFalse();
    }

    @Test
    void andNeedsBoth() {
        assertThat(check("unit = payment-kernel and stream = stdout")).isTrue();
        assertThat(check("unit = payment-kernel and stream = stderr")).isFalse();
    }

    @Test
    void orNeedsEither() {
        assertThat(check("unit = tank-gauge or stream = stdout")).isTrue();
        assertThat(check("unit = tank-gauge or stream = stderr")).isFalse();
    }

    @Test
    void notInverts() {
        assertThat(check("not unit = tank-gauge")).isTrue();
        assertThat(check("not unit = payment-kernel")).isFalse();
    }

    @Test
    void aSinceWindowUsesTheObservedTime() {
        assertThat(check("since 2h")).isTrue();
        assertThat(check("since 30m")).isFalse();
    }

    @Test
    void anUntilWindowUsesTheObservedTime() {
        assertThat(check("until 30m")).isTrue();
        assertThat(check("until 2h")).isFalse();
    }

    @Test
    void aWindowPrefersTheCorrectedTime() {
        StoredRecord corrected = record("x", at("2025-03-04T09:00:00Z"), Optional.of(at("2025-03-04T11:59:00Z")));
        assertThat(check("since 30m", corrected)).isTrue();
    }

    @Test
    void theWindowBoundaryIsInclusive() {
        StoredRecord item = record("x", at("2025-03-04T10:00:00Z"), Optional.empty());
        assertThat(check("since 2h", item)).isTrue();
        assertThat(check("until 2h", item)).isTrue();
    }

    @Test
    void aCompoundFilterMatches() {
        assertThat(check("class = payment and since 2h and not message ~ heartbeat"))
                .isTrue();
    }

    @Test
    void anUnknownNodeTypeIsRefused() {
        assertThatThrownBy(() -> Evaluate.matches(new Odd(), record()))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("unknown filter node");
    }

    @Test
    void runFiltersAList() {
        List<StoredRecord> records = List.of(record("one"), record("two"), record("three"));
        List<StoredRecord> found = Evaluate.run(Parser.parseQuery("message ~ t", NOW), records);
        assertThat(found).extracting(StoredRecord::message).containsExactly("two", "three");
    }

    @Test
    void runHonoursTheLimit() {
        List<StoredRecord> records =
                IntStream.range(0, 5).mapToObj(index -> record("line " + index)).toList();
        assertThat(Evaluate.run(Parser.parseQuery("limit 2", NOW), records)).hasSize(2);
    }

    @Test
    void runWithoutALimitReturnsEverythingMatching() {
        List<StoredRecord> records =
                IntStream.range(0, 5).mapToObj(index -> record("line " + index)).toList();
        assertThat(Evaluate.run(Parser.parseQuery("", NOW), records)).hasSize(5);
    }

    @Test
    void countIgnoresTheLimit() {
        List<StoredRecord> records =
                IntStream.range(0, 5).mapToObj(index -> record("line " + index)).toList();
        assertThat(Evaluate.count(Parser.parseQuery("limit 2", NOW), records)).isEqualTo(5);
    }

    @Test
    void aCompareNodeCanBeEvaluatedDirectly() {
        assertThat(Evaluate.matches(new Compare("unit", Op.EQ, "payment-kernel"), record()))
                .isTrue();
    }

    @Test
    void aLiveRecordHasNoClassAndNoStoredConfidence() {
        LogRecord live = new LogRecord("US-TX-0417", "pump-bridge", Stream.STDERR, at("2025-03-04T11:30:00Z"), "x")
                .withLabel("lane", "1");
        assertThat(check("unit = pump-bridge and stream = stderr and label.lane = 1 and since 1h", live))
                .isTrue();
        assertThat(check("class = payment", live)).isFalse();
        assertThat(check("confidence != measured", live)).isTrue();
        assertThat(check("confidence = measured", live)).isFalse();
    }
}
