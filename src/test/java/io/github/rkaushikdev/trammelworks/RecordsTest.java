package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordsTest {

    static final String TS = "2025-03-04T11:30:00.123456789Z";

    static NanoTime ts() {
        return ts(TS);
    }

    static NanoTime ts(String raw) {
        return NanoTime.parse(raw);
    }

    static SourceRef ref() {
        return ref(0, 64);
    }

    static SourceRef ref(long offset, long length) {
        return new SourceRef("/var/log/containers/payment.log", offset, length);
    }

    static LogRecord record() {
        return new LogRecord("US-TX-0417", "payment-kernel", Stream.STDOUT, ts(), "auth approved");
    }

    @Test
    void streamParseAcceptsExactValues() {
        assertThat(Stream.parse("stdout")).isSameAs(Stream.STDOUT);
        assertThat(Stream.parse("stderr")).isSameAs(Stream.STDERR);
    }

    @Test
    void streamParseIsForgivingAboutCaseAndPadding() {
        assertThat(Stream.parse("  STDERR \n")).isSameAs(Stream.STDERR);
    }

    @Test
    void streamParseRejectsJunk() {
        assertThatThrownBy(() -> Stream.parse("stdin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown stream");
    }

    @Test
    void streamIsAString() {
        assertThat(Stream.STDOUT.value()).isEqualTo("stdout");
        assertThat(String.valueOf(Stream.STDERR)).isEqualTo("stderr");
    }

    @Test
    void sourceRefEndIsOffsetPlusLength() {
        assertThat(ref(100, 20).end()).isEqualTo(120);
    }

    @Test
    void sourceRefRejectsNegativeOffset() {
        assertThatThrownBy(() -> new SourceRef("a", -1, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative offset");
    }

    @Test
    void sourceRefRejectsNegativeLength() {
        assertThatThrownBy(() -> new SourceRef("a", 0, -4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative length");
    }

    @Test
    void sourceRefAllowsAZeroLengthLine() {
        assertThat(new SourceRef("a", 8, 0).end()).isEqualTo(8);
    }

    @Test
    void sourceRefShiftedMovesOnlyTheOffset() {
        SourceRef moved = ref(10, 5).shifted(90);
        assertThat(moved.offset()).isEqualTo(100);
        assertThat(moved.length()).isEqualTo(5);
        assertThat(moved.path()).isEqualTo(ref().path());
    }

    @Test
    void sourceRefStrIsGreppable() {
        assertThat(ref(7, 3).toString()).isEqualTo("/var/log/containers/payment.log@7+3");
    }

    @Test
    void fragmentKeepsAllNineDigits() {
        Fragment frag = new Fragment(ts(), Stream.STDOUT, "hi", ref());
        assertThat(frag.observedAt().nanosInSecond()).isEqualTo(123456789);
    }

    @Test
    void fragmentToDatetimeTruncatesToTheMicrosecond() {
        Fragment frag = new Fragment(ts(), Stream.STDOUT, "hi", ref());
        assertThat(frag.toDateTime()).isEqualTo(OffsetDateTime.of(2025, 3, 4, 11, 30, 0, 123456000, ZoneOffset.UTC));
    }

    @Test
    void fragmentDefaultsToCompleteAndClean() {
        Fragment frag = new Fragment(ts(), Stream.STDOUT, "hi", ref());
        assertThat(frag.partial()).isFalse();
        assertThat(frag.complete()).isTrue();
        assertThat(frag.lossy()).isFalse();
    }

    @Test
    void fragmentCompleteIsTheOppositeOfPartial() {
        Fragment frag = new Fragment(ts(), Stream.STDOUT, "hi", ref(), true);
        assertThat(frag.complete()).isFalse();
    }

    @Test
    void fragmentWithMessageKeepsEverythingElse() {
        Fragment frag = new Fragment(ts(), Stream.STDERR, "ab", ref(), true, true, Map.of());
        Fragment swapped = frag.withMessage("abcd");
        assertThat(swapped.message()).isEqualTo("abcd");
        assertThat(swapped.partial()).isTrue();
        assertThat(swapped.lossy()).isTrue();
        assertThat(swapped.stream()).isSameAs(Stream.STDERR);
        assertThat(swapped.source()).isEqualTo(frag.source());
    }

    @Test
    void twoFragmentsWithTheSameContentAreEqual() {
        Fragment left = new Fragment(ts(), Stream.STDOUT, "hi", ref());
        Fragment right = new Fragment(ts(), Stream.STDOUT, "hi", ref());
        assertThat(left).isEqualTo(right);
    }

    @Test
    void labelsArePartOfWhatMakesAFragmentEqual() {
        Fragment plain = new Fragment(ts(), Stream.STDOUT, "hi", ref());
        Fragment tagged =
                new Fragment(ts(), Stream.STDOUT, "hi", ref(), false, false, Map.of("boot_id", "b".repeat(32)));
        assertThat(plain).isNotEqualTo(tagged);
    }

    @Test
    void aFragmentCarryingLabelsIsNoLongerHashable() {
        // The reference refused to hash a fragment once it carried a mapping. A Java record always
        // hashes, so what is pinned here is the contract that replaces it: the labels take part in
        // the hash exactly as they take part in equality. See PROGRESS open items.
        Fragment plain = new Fragment(ts(), Stream.STDOUT, "hi", ref());
        Fragment tagged = plain.withLabels(Map.of("boot_id", "b".repeat(32)));
        assertThat(tagged.hashCode())
                .isEqualTo(plain.withLabels(Map.of("boot_id", "b".repeat(32))).hashCode());
        assertThat(tagged.hashCode()).isNotEqualTo(plain.hashCode());
    }

    @Test
    void recordRejectsAnEmptySite() {
        assertThatThrownBy(() -> record().withSite(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("site must not be empty");
    }

    @Test
    void recordRejectsAnEmptyUnit() {
        assertThatThrownBy(() -> record().withUnit(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unit must not be empty");
    }

    @Test
    void recordByteLengthCountsUtf8NotCharacters() {
        assertThat(record().withMessage("abc").byteLength()).isEqualTo(3);
        assertThat(record().withMessage("prix affýché").byteLength()).isEqualTo(14);
    }

    @Test
    void recordFragmentCountFollowsTheSources() {
        assertThat(record().fragmentCount()).isZero();
        assertThat(record().withSources(List.of(ref(0, 10), ref(10, 10))).fragmentCount())
                .isEqualTo(2);
    }

    @Test
    void recordToDatetimeLosesTheLastThreeDigits() {
        assertThat(record().toDateTime().getNano() / 1000).isEqualTo(123456);
    }

    @Test
    void twoRecordsOneNanosecondApartStayOrdered() {
        LogRecord early = record().withObservedAt(ts("2025-03-04T11:30:00.000000001Z"));
        LogRecord late = record().withObservedAt(ts("2025-03-04T11:30:00.000000002Z"));
        assertThat(early.observedAt()).isLessThan(late.observedAt());
        assertThat(early.toDateTime()).isEqualTo(late.toDateTime());
    }

    @Test
    void withLabelsMergesAndDoesNotMutate() {
        LogRecord original = record().replaceLabels(Map.of("lane", "3"));
        LogRecord tagged = original.withLabel("pump", "7");
        assertThat(tagged.labels()).isEqualTo(Map.of("lane", "3", "pump", "7"));
        assertThat(original.labels()).isEqualTo(Map.of("lane", "3"));
    }

    @Test
    void withLabelsOverwritesARepeatedKey() {
        assertThat(record().replaceLabels(Map.of("lane", "3"))
                        .withLabel("lane", "4")
                        .labels())
                .isEqualTo(Map.of("lane", "4"));
    }

    @Test
    void asDictRendersAllNineDigits() {
        Map<String, Object> payload = record().asDict();
        assertThat(payload.get("observed_at")).isEqualTo("2025-03-04T11:30:00.123456789Z");
        assertThat(payload.get("stream")).isEqualTo("stdout");
    }

    @Test
    void asDictStringifiesTheSources() {
        Map<String, Object> payload = record().withSources(List.of(ref(0, 10))).asDict();
        assertThat(payload.get("sources")).isEqualTo(List.of("/var/log/containers/payment.log@0+10"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void asDictCopiesTheLabels() {
        LogRecord original = record().replaceLabels(Map.of("lane", "3"));
        Map<String, Object> payload = original.asDict();
        assertThat(payload.get("labels")).isInstanceOf(HashMap.class);
        ((Map<String, String>) payload.get("labels")).put("lane", "9");
        assertThat(original.labels()).isEqualTo(Map.of("lane", "3"));
    }

    @Test
    void aRecordMayBeMarkedLossy() {
        assertThat(record().withLossy(true).lossy()).isTrue();
        assertThat(record().lossy()).isFalse();
    }
}
