package io.github.rkaushikdev.trammelworks.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.SourceRef;
import io.github.rkaushikdev.trammelworks.Stream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IngestMultilineTest {

    static final String UNIT = "payment-kernel";

    static LogRecord rec(String message) {
        return rec(message, Stream.STDOUT, "11:30:00");
    }

    static LogRecord rec(String message, Stream stream) {
        return rec(message, stream, "11:30:00");
    }

    static LogRecord rec(String message, Stream stream, String at) {
        return new LogRecord("US-TX-0417", UNIT, stream, NanoTime.parse("2025-03-04T" + at + "Z"), message);
    }

    static MultilineRule indentRule() {
        return MultilineRule.continuing(UNIT, "^\\s+");
    }

    static MultilineRule seqRule() {
        return MultilineRule.starting(UNIT, "^\\d+ ");
    }

    @Test
    void aRuleNeedsOneFraming() {
        assertThatThrownBy(() -> new MultilineRule(UNIT, null, null, MultilineRule.DEFAULT_MAX_LINES, false))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("exactly one of start or continuation");
    }

    @Test
    void aRuleRefusesBothFramings() {
        assertThatThrownBy(() -> new MultilineRule(UNIT, "^a", "^\\s", MultilineRule.DEFAULT_MAX_LINES, false))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("exactly one of start or continuation");
    }

    @Test
    void aRuleRefusesAMaxBelowTwo() {
        assertThatThrownBy(() -> indentRule().withMaxLines(1))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("at least 2");
    }

    @Test
    void aRuleRefusesAPatternThatDoesNotCompile() {
        assertThatThrownBy(() -> MultilineRule.continuing(UNIT, "(unclosed"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("does not compile");
    }

    @Test
    void continuationMatchesAnIndentedFrame() {
        MultilineRule rule = indentRule();
        assertThat(rule.continues("    at Pump.reset(Pump.java:41)")).isTrue();
        assertThat(rule.continues("something happened")).isFalse();
    }

    @Test
    void startFramingIsTheInverse() {
        MultilineRule rule = seqRule();
        assertThat(rule.continues("0001 auth requested")).isFalse();
        assertThat(rule.continues("  stack frame")).isTrue();
    }

    @Test
    void negateFlipsAContinuationRule() {
        MultilineRule rule = indentRule().withNegate(true);
        assertThat(rule.continues("    indented")).isFalse();
        assertThat(rule.continues("plain")).isTrue();
    }

    @Test
    void aSingleLinePassesThroughUnchanged() {
        Stitcher stitcher = new Stitcher(indentRule());
        assertThat(stitcher.feed(rec("first"))).isEmpty();
        List<LogRecord> out = stitcher.feed(rec("second"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).message()).isEqualTo("first");
        assertThat(out.get(0).labels()).isEmpty();
    }

    @Test
    void indentedLinesAreFoldedIntoTheLineAbove() {
        Stitcher stitcher = new Stitcher(indentRule());
        stitcher.feed(rec("java.lang.IllegalStateException: pump busy"));
        stitcher.feed(rec("    at Pump.reset(Pump.java:41)"));
        stitcher.feed(rec("    at Main.run(Main.java:9)"));
        List<LogRecord> out = stitcher.feed(rec("next event"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).message().lines())
                .containsExactly(
                        "java.lang.IllegalStateException: pump busy",
                        "    at Pump.reset(Pump.java:41)",
                        "    at Main.run(Main.java:9)");
    }

    @Test
    void theStitchedRecordSaysHowManyLinesItHolds() {
        Stitcher stitcher = new Stitcher(indentRule());
        stitcher.feed(rec("head"));
        stitcher.feed(rec("  one"));
        assertThat(stitcher.feed(rec("next")).get(0).labels().get("stitched")).isEqualTo("2");
    }

    @Test
    void theFirstLineOwnsTheTimestamp() {
        Stitcher stitcher = new Stitcher(indentRule());
        stitcher.feed(rec("head", Stream.STDOUT, "11:30:00"));
        stitcher.feed(rec("  one", Stream.STDOUT, "11:30:09"));
        List<LogRecord> out = stitcher.feed(rec("next", Stream.STDOUT, "11:31:00"));
        assertThat(out.get(0).observedAt()).isEqualTo(NanoTime.parse("2025-03-04T11:30:00Z"));
    }

    @Test
    void theStreamsAreHeldSeparately() {
        Stitcher stitcher = new Stitcher(indentRule());
        stitcher.feed(rec("out head", Stream.STDOUT));
        stitcher.feed(rec("err head", Stream.STDERR));
        stitcher.feed(rec("  err frame", Stream.STDERR));
        List<LogRecord> out = stitcher.feed(rec("out next", Stream.STDOUT));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).message()).isEqualTo("out head");
        assertThat(stitcher.heldStreams()).containsExactly(Stream.STDOUT, Stream.STDERR);
    }

    @Test
    void aContinuationOnTheOtherStreamDoesNotJoin() {
        Stitcher stitcher = new Stitcher(indentRule());
        stitcher.feed(rec("head", Stream.STDOUT));
        stitcher.feed(rec("  frame", Stream.STDERR));
        assertThat(stitcher.flush()).extracting(LogRecord::message).containsExactlyInAnyOrder("  frame", "head");
    }

    @Test
    void flushEmitsWhatIsHeld() {
        Stitcher stitcher = new Stitcher(indentRule());
        stitcher.feed(rec("head"));
        stitcher.feed(rec("  frame"));
        List<LogRecord> out = stitcher.flush();
        assertThat(out).hasSize(1);
        assertThat(out.get(0).labels().get("stitched")).isEqualTo("2");
        assertThat(stitcher.heldStreams()).isEmpty();
    }

    @Test
    void flushOfAnEmptyStitcherGivesNothing() {
        assertThat(new Stitcher(indentRule()).flush()).isEmpty();
    }

    @Test
    void aRunLongerThanTheCapStopsGrowing() {
        Stitcher stitcher = new Stitcher(indentRule().withMaxLines(3));
        stitcher.feed(rec("head"));
        for (int index = 0; index < 5; index++) {
            stitcher.feed(rec("  frame " + index));
        }
        LogRecord out = stitcher.flush().get(0);
        assertThat(out.message().chars().filter(c -> c == '\n').count()).isEqualTo(2);
        assertThat(out.labels().get("truncated")).isEqualTo("true");
    }

    @Test
    void theFirstLineAfterACappedRunStartsANewEvent() {
        Stitcher stitcher = new Stitcher(indentRule().withMaxLines(2));
        stitcher.feed(rec("head"));
        stitcher.feed(rec("  one"));
        stitcher.feed(rec("  two"));
        List<LogRecord> out = stitcher.feed(rec("plain"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).message()).isEqualTo("head\n  one");
    }

    @Test
    void sourcesFromEveryLineAreKept() {
        Stitcher stitcher = new Stitcher(indentRule());
        LogRecord head = rec("head").withSources(List.of(new SourceRef("a", 0, 10)));
        LogRecord frame = rec("  frame").withSources(List.of(new SourceRef("a", 10, 10)));
        stitcher.feed(head);
        stitcher.feed(frame);
        assertThat(stitcher.flush().get(0).sources())
                .extracting(SourceRef::offset)
                .containsExactly(0L, 10L);
    }

    @Test
    void rulesFromMappingBuildsTheTable() {
        Map<String, MultilineRule> table =
                MultilineRule.rulesFromMapping(Map.of(UNIT, Map.of("continuation", "^\\s+", "max_lines", 40)));
        assertThat(table.get(UNIT).maxLines()).isEqualTo(40);
        assertThat(table.get(UNIT).continuation()).contains("^\\s+");
    }

    @Test
    void rulesFromMappingRejectsANonMapping() {
        assertThatThrownBy(() -> MultilineRule.rulesFromMapping(Map.of(UNIT, "indent")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be a mapping");
    }

    @Test
    void rulesFromMappingRejectsAnUnknownKey() {
        assertThatThrownBy(
                        () -> MultilineRule.rulesFromMapping(Map.of(UNIT, Map.of("continuation", "^x", "maxlines", 4))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown multiline key");
    }

    @Test
    void rulesFromMappingStillRefusesBothFramings() {
        assertThatThrownBy(
                        () -> MultilineRule.rulesFromMapping(Map.of(UNIT, Map.of("continuation", "^x", "start", "^y"))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("exactly one of");
    }
}
