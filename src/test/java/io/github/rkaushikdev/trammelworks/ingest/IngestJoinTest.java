package io.github.rkaushikdev.trammelworks.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.Fragment;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.SourceRef;
import io.github.rkaushikdev.trammelworks.Stream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IngestJoinTest {

    static final String SITE = "US-TX-0417";
    static final String UNIT = "payment-kernel";
    static final String AT = "2025-03-04T11:30:00.000000001Z";

    static Fragment frag(String message) {
        return frag(message, false, Stream.STDOUT, AT, 0, false);
    }

    static Fragment partial(String message) {
        return frag(message, true, Stream.STDOUT, AT, 0, false);
    }

    static Fragment frag(String message, boolean partial, Stream stream, String at, long offset, boolean lossy) {
        return new Fragment(
                NanoTime.parse(at),
                stream,
                message,
                new SourceRef("/var/log/pods/x/0.log", offset, message.length() + 40),
                partial,
                lossy,
                Map.of());
    }

    static Joiner joiner() {
        return new Joiner(SITE, UNIT);
    }

    static Joiner joiner(JoinLimits limits) {
        return new Joiner(SITE, UNIT, limits);
    }

    @Test
    void aCompleteFragmentPassesStraightThrough() {
        Optional<LogRecord> record = joiner().feed(frag("auth approved"));
        assertThat(record).isPresent();
        assertThat(record.get().message()).isEqualTo("auth approved");
        assertThat(record.get().site()).isEqualTo(SITE);
        assertThat(record.get().unit()).isEqualTo(UNIT);
        assertThat(record.get().fragmentCount()).isEqualTo(1);
    }

    @Test
    void aPartialFragmentProducesNothingYet() {
        assertThat(joiner().feed(partial("half"))).isEmpty();
    }

    @Test
    void twoChunksBecomeOneRecord() {
        Joiner joined = joiner();
        assertThat(joined.feed(partial("hello "))).isEmpty();
        LogRecord record = joined.feed(frag("world")).orElseThrow();
        assertThat(record.message()).isEqualTo("hello world");
        assertThat(record.fragmentCount()).isEqualTo(2);
    }

    @Test
    void manyChunksJoinInOrder() {
        Joiner joined = joiner();
        for (String part : List.of("a", "b", "c", "d")) {
            joined.feed(partial(part));
        }
        LogRecord record = joined.feed(frag("e")).orElseThrow();
        assertThat(record.message()).isEqualTo("abcde");
        assertThat(record.fragmentCount()).isEqualTo(5);
    }

    @Test
    void theFirstChunkOwnsTheTimestamp() {
        Joiner joined = joiner();
        joined.feed(frag("start", true, Stream.STDOUT, "2025-03-04T11:30:00.000000001Z", 0, false));
        LogRecord record = joined.feed(frag("end", false, Stream.STDOUT, "2025-03-04T11:31:59.999999999Z", 0, false))
                .orElseThrow();
        assertThat(record.observedAt()).isEqualTo(NanoTime.parse("2025-03-04T11:30:00.000000001Z"));
    }

    @Test
    void theStreamsAreJoinedIndependently() {
        Joiner joined = joiner();
        joined.feed(frag("out ", true, Stream.STDOUT, AT, 0, false));
        joined.feed(frag("err ", true, Stream.STDERR, AT, 0, false));
        LogRecord out =
                joined.feed(frag("done", false, Stream.STDOUT, AT, 0, false)).orElseThrow();
        LogRecord err =
                joined.feed(frag("boom", false, Stream.STDERR, AT, 0, false)).orElseThrow();
        assertThat(out.message()).isEqualTo("out done");
        assertThat(err.message()).isEqualTo("err boom");
    }

    @Test
    void aCompleteLineOnOneStreamDoesNotDisturbTheOther() {
        Joiner joined = joiner();
        joined.feed(frag("pending ", true, Stream.STDERR, AT, 0, false));
        assertThat(joined.feed(frag("heartbeat", false, Stream.STDOUT, AT, 0, false)))
                .isPresent();
        assertThat(joined.pendingStreams()).containsExactly(Stream.STDERR);
    }

    @Test
    void theRecordKeepsEverySource() {
        Joiner joined = joiner();
        joined.feed(frag("a", true, Stream.STDOUT, AT, 0, false));
        LogRecord record =
                joined.feed(frag("b", false, Stream.STDOUT, AT, 100, false)).orElseThrow();
        assertThat(record.sources()).extracting(SourceRef::offset).containsExactly(0L, 100L);
    }

    @Test
    void theStreamOfTheRecordIsTheFirstChunks() {
        Joiner joined = joiner();
        joined.feed(frag("x", true, Stream.STDERR, AT, 0, false));
        assertThat(joined.feed(frag("y", false, Stream.STDERR, AT, 0, false)).orElseThrow().stream())
                .isSameAs(Stream.STDERR);
    }

    @Test
    void anEmptyFinalChunkStillClosesTheLine() {
        Joiner joined = joiner();
        joined.feed(partial("body"));
        assertThat(joined.feed(frag("")).orElseThrow().message()).isEqualTo("body");
    }

    @Test
    void pendingStreamsEmptiesOnceTheLineCloses() {
        Joiner joined = joiner();
        joined.feed(partial("x"));
        joined.feed(frag("y"));
        assertThat(joined.pendingStreams()).isEmpty();
    }

    @Test
    void aLineOverTheCapIsCutAndLabelled() {
        Joiner joined = joiner(new JoinLimits(10));
        joined.feed(partial("0123456789"));
        LogRecord record = joined.feed(frag("this is dropped")).orElseThrow();
        assertThat(record.message()).isEqualTo("0123456789");
        assertThat(record.labels().get("truncated")).isEqualTo("true");
    }

    @Test
    void theCutLandsOnTheByteBoundary() {
        Joiner joined = joiner(new JoinLimits(6));
        joined.feed(partial("abcd"));
        assertThat(joined.feed(frag("efgh")).orElseThrow().message()).isEqualTo("abcdef");
    }

    @Test
    void theCutNeverSplitsAMultibyteCharacter() {
        Joiner joined = joiner(new JoinLimits(5));
        joined.feed(partial("ab"));
        LogRecord record = joined.feed(frag("é".repeat(4))).orElseThrow();
        assertThat(record.message()).isEqualTo("abé");
        assertThat(record.byteLength()).isLessThanOrEqualTo(5);
    }

    @Test
    void chunksAfterTheCapAreDroppedNotBuffered() {
        Joiner joined = joiner(new JoinLimits(4));
        joined.feed(partial("abcd"));
        joined.feed(partial("efgh"));
        joined.feed(partial("ijkl"));
        assertThat(joined.feed(frag("mnop")).orElseThrow().message()).isEqualTo("abcd");
        assertThat(joined.stats().droppedChunks()).isEqualTo(3);
    }

    @Test
    void aCappedLineStillEndsWhenTheFinalChunkArrives() {
        Joiner joined = joiner(new JoinLimits(4));
        joined.feed(partial("abcdefgh"));
        joined.feed(frag("done"));
        assertThat(joined.pendingStreams()).isEmpty();
    }

    @Test
    void theChunkCountCapAlsoTruncates() {
        Joiner joined = joiner(new JoinLimits(10_000, 3));
        for (int index = 0; index < 3; index++) {
            joined.feed(partial("x"));
        }
        LogRecord record = joined.feed(frag("y")).orElseThrow();
        assertThat(record.message()).isEqualTo("xxx");
        assertThat(record.labels().get("truncated")).isEqualTo("true");
    }

    @Test
    void limitsRejectANonPositiveCap() {
        assertThatThrownBy(() -> new JoinLimits(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_line_bytes must be positive");
        assertThatThrownBy(() -> new JoinLimits(JoinLimits.DEFAULT_MAX_LINE_BYTES, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max_chunks must be positive");
    }

    @Test
    void flushEmitsAPendingLineAsTruncated() {
        Joiner joined = joiner();
        joined.feed(partial("unfinished"));
        List<LogRecord> records = joined.flush();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).message()).isEqualTo("unfinished");
        assertThat(records.get(0).labels().get("truncated")).isEqualTo("true");
        assertThat(joined.pendingStreams()).isEmpty();
    }

    @Test
    void flushWithNothingPendingReturnsNothing() {
        assertThat(joiner().flush()).isEmpty();
    }

    @Test
    void flushCanTargetOneStream() {
        Joiner joined = joiner();
        joined.feed(frag("out", true, Stream.STDOUT, AT, 0, false));
        joined.feed(frag("err", true, Stream.STDERR, AT, 0, false));
        assertThat(joined.flush(Stream.STDOUT)).hasSize(1);
        assertThat(joined.pendingStreams()).containsExactly(Stream.STDERR);
    }

    @Test
    void flushOfAnAbsentStreamIsANoOp() {
        assertThat(joiner().flush(Stream.STDERR)).isEmpty();
    }

    @Test
    void aLossyChunkMarksTheWholeRecord() {
        Joiner joined = joiner();
        joined.feed(partial("good "));
        LogRecord record =
                joined.feed(frag("bad", false, Stream.STDOUT, AT, 0, true)).orElseThrow();
        assertThat(record.lossy()).isTrue();
        assertThat(record.labels().get("lossy")).isEqualTo("true");
    }

    @Test
    void aLossySingleLineIsMarked() {
        assertThat(joiner().feed(frag("bad", false, Stream.STDOUT, AT, 0, true))
                        .orElseThrow()
                        .lossy())
                .isTrue();
    }

    @Test
    void aCleanRecordCarriesNoLabels() {
        assertThat(joiner().feed(frag("fine")).orElseThrow().labels()).isEmpty();
    }

    @Test
    void theStatsAddUp() {
        Joiner joined = joiner();
        joined.feed(partial("a"));
        joined.feed(frag("b"));
        joined.feed(frag("c"));
        assertThat(joined.stats().fragments()).isEqualTo(3);
        assertThat(joined.stats().records()).isEqualTo(2);
        assertThat(joined.stats().joined()).isEqualTo(1);
        assertThat(joined.stats().truncated()).isZero();
    }

    @Test
    void flushIsCountedSeparately() {
        Joiner joined = joiner();
        joined.feed(partial("open"));
        joined.flush();
        assertThat(joined.stats().flushedOpen()).isEqualTo(1);
        assertThat(joined.stats().truncated()).isEqualTo(1);
    }

    @Test
    void fragmentLabelsReachASingleLineRecord() {
        Fragment tagged = new Fragment(
                NanoTime.parse("2025-03-04T11:30:00Z"),
                Stream.STDOUT,
                "x",
                new SourceRef("a", 0, 1),
                false,
                false,
                Map.of("boot_id", "b".repeat(32)));
        assertThat(joiner().feed(tagged).orElseThrow().labels().get("boot_id")).isEqualTo("b".repeat(32));
    }

    @Test
    void theFirstChunkOwnsTheLabels() {
        Joiner joined = joiner();
        Fragment first = new Fragment(
                NanoTime.parse("2025-03-04T11:30:00Z"),
                Stream.STDOUT,
                "head ",
                new SourceRef("a", 0, 1),
                true,
                false,
                Map.of("monotonic_ns", "100"));
        Fragment tail = new Fragment(
                NanoTime.parse("2025-03-04T11:30:01Z"),
                Stream.STDOUT,
                "tail",
                new SourceRef("a", 1, 1),
                false,
                false,
                Map.of("monotonic_ns", "900"));
        joined.feed(first);
        assertThat(joined.feed(tail).orElseThrow().labels().get("monotonic_ns")).isEqualTo("100");
    }
}
