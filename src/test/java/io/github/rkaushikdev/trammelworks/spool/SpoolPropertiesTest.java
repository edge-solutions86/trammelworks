package io.github.rkaushikdev.trammelworks.spool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Samples;
import io.github.rkaushikdev.trammelworks.SpoolError;
import io.github.rkaushikdev.trammelworks.Stream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Properties the spool has to hold for any input, not just the examples: a frame round trips, a
 * torn tail loses only the torn frame, and the budget is never over. The spool is where being
 * wrong is silent, because a frame that decodes to something slightly different still decodes.
 */
class SpoolPropertiesTest {

    static final List<String> UNITS = List.of("pump-bridge", "price-sign", "payment-kernel", "tank-gauge");
    static final String TEXT = "abcdefghijklmnopqrstuvwxyz ABCXYZ 0123456789 .,;:=-_/\\\"'\n\téüß€中文\u0000\u007f😀";
    static final String LABEL_KEYS = "abcdefghijklmnopqrstuvwxyz_";
    static final PriorityTable TABLE = new PriorityTable(Map.of("payment-kernel", "payment", "price-sign", "chatter"));

    @TempDir
    Path tmp;

    static LogRecord record(Random random) {
        Map<String, String> labels = new LinkedHashMap<>();
        int count = random.nextInt(5);
        for (int index = 0; index < count; index++) {
            labels.put(Samples.text(random, LABEL_KEYS, 1, 8), Samples.text(random, TEXT, 0, 16));
        }
        return new LogRecord(
                        "US-TX-0417",
                        UNITS.get(random.nextInt(UNITS.size())),
                        Stream.values()[random.nextInt(2)],
                        new NanoTime(random.nextInt(2_000_000_001) * 1_000_000_000L),
                        Samples.text(random, TEXT, 0, 400))
                .replaceLabels(labels);
    }

    static List<LogRecord> batch(Random random, int min, int max) {
        int size = min + random.nextInt(max - min + 1);
        List<LogRecord> out = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            out.add(record(random));
        }
        return out;
    }

    static java.util.stream.Stream<Map<String, Object>> payloads() {
        return Samples.drawn(101, random -> record(random).asDict());
    }

    static java.util.stream.Stream<Arguments> payloadsAndFlags() {
        Random flags = Samples.random(7);
        return payloads().map(payload -> Arguments.of(payload, flags.nextBoolean()));
    }

    static byte[] concat(List<byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        parts.forEach(out::writeBytes);
        return out.toByteArray();
    }

    @ParameterizedTest
    @MethodSource("payloadsAndFlags")
    void aFrameRoundTrips(Map<String, Object> payload, boolean compress) {
        assertThat(Frames.decodeFrame(Frames.encodeFrame(payload, compress))
                        .frame()
                        .payload())
                .isEqualTo(normalized(payload));
    }

    /** What a payload reads back as: integers come back as longs, every other value unchanged. */
    static Map<String, Object> normalized(Map<String, Object> payload) {
        return io.github.rkaushikdev.trammelworks.Json.loadsObject(
                io.github.rkaushikdev.trammelworks.Json.dumps(payload));
    }

    @ParameterizedTest
    @MethodSource("payloads")
    void compressionDoesNotChangeWhatComesBack(Map<String, Object> payload) {
        Frame plain = Frames.decodeFrame(Frames.encodeFrame(payload, false)).frame();
        Frame squeezed = Frames.decodeFrame(Frames.encodeFrame(payload, true)).frame();
        assertThat(plain.payload()).isEqualTo(squeezed.payload());
    }

    @ParameterizedTest
    @MethodSource("payloadsAndFlags")
    void aFrameReportsTheSizeItOccupies(Map<String, Object> payload, boolean compress) {
        byte[] raw = Frames.encodeFrame(payload, compress);
        Frames.Decoded decoded = Frames.decodeFrame(raw);
        assertThat(decoded.frame().storedSize()).isEqualTo(raw.length);
        assertThat(decoded.next()).isEqualTo(raw.length);
    }

    static java.util.stream.Stream<List<Map<String, Object>>> runs() {
        return Samples.drawn(
                103,
                random -> batch(random, 1, 12).stream().map(LogRecord::asDict).toList());
    }

    @ParameterizedTest
    @MethodSource("runs")
    void everyFrameInARunComesBackInOrder(List<Map<String, Object>> items) {
        byte[] data = concat(items.stream().map(Frames::encodeFrame).toList());
        Frames.Run run = Frames.iterFrames(data);
        assertThat(run.note()).isEmpty();
        assertThat(run.consumed()).isEqualTo(data.length);
        assertThat(run.frames()).extracting(Frame::payload).isEqualTo(items);
    }

    static java.util.stream.Stream<Arguments> tornRuns() {
        return Samples.drawn(107, random -> {
            List<Map<String, Object>> items =
                    batch(random, 2, 8).stream().map(LogRecord::asDict).toList();
            return Arguments.of(items, 1 + random.nextInt(64));
        });
    }

    @ParameterizedTest
    @MethodSource("tornRuns")
    void aTornTailLosesOnlyTheTornFrame(List<Map<String, Object>> items, int bite) {
        List<byte[]> encoded = items.stream().map(Frames::encodeFrame).toList();
        byte[] data = concat(encoded);
        int last = encoded.get(encoded.size() - 1).length;
        int cut = Math.min(bite, last - 1);
        Frames.Run run = Frames.iterFrames(Arrays.copyOf(data, data.length - cut));
        assertThat(run.frames()).extracting(Frame::payload).isEqualTo(items.subList(0, items.size() - 1));
        assertThat(run.consumed()).isEqualTo(data.length - last);
    }

    @ParameterizedTest
    @MethodSource("payloads")
    void aFrameWithAFlippedByteDoesNotDecodeQuietly(Map<String, Object> payload) {
        byte[] raw = Frames.encodeFrame(payload, false);
        assertThat(raw.length).isGreaterThan(Frames.OVERHEAD + 1);
        raw[Frames.OVERHEAD] ^= (byte) 0xFF;
        Optional<Frame> decoded;
        try {
            decoded = Optional.of(Frames.decodeFrame(raw).frame());
        } catch (SpoolError refused) {
            decoded = Optional.empty();
        }
        decoded.ifPresent(frame -> assertThat(frame.payload()).isNotEqualTo(payload));
    }

    static java.util.stream.Stream<Arguments> sequencesAndClasses() {
        Random classes = Samples.random(13);
        return Samples.longs(0, 999_999_999_999L)
                .map(sequence -> Arguments.of(sequence, classes.nextBoolean() ? "payment" : "chatter"));
    }

    @ParameterizedTest
    @MethodSource("sequencesAndClasses")
    void aSegmentNameRoundTrips(long sequence, String klass) {
        assertThat(Segments.parseSegmentName(Segments.segmentName(sequence, klass)))
                .contains(new Segments.Name(sequence, klass));
    }

    static java.util.stream.Stream<Long> sequences() {
        return Samples.longs(0, 999_999_999_999L);
    }

    @ParameterizedTest
    @MethodSource("sequences")
    void segmentNamesSortTheWayTheNumbersDo(long sequence) {
        long later = Math.min(sequence + 1, 999_999_999_999L);
        assertThat(Segments.segmentName(sequence, "payment").compareTo(Segments.segmentName(later, "payment")))
                .isLessThanOrEqualTo(0);
    }

    static java.util.stream.Stream<List<LogRecord>> batches() {
        return Samples.drawn(109, random -> batch(random, 1, 25));
    }

    static java.util.stream.Stream<List<LogRecord>> longBatches() {
        return Samples.drawn(113, random -> batch(random, 1, 40));
    }

    static java.util.stream.Stream<List<LogRecord>> shortBatches() {
        return Samples.drawn(127, random -> batch(random, 1, 20));
    }

    Spool.Builder fresh() {
        return Spool.builder(tmp.resolve("spool"), new Budget(4_000_000))
                .segmentBytes(4096)
                .compress(false);
    }

    static List<Map<String, Object>> everything(Path directory) {
        List<Map<String, Object>> found = new ArrayList<>();
        for (SegmentInfo info : Segments.listSegments(directory)) {
            found.addAll(SegmentReader.readSegment(info.path()).payloads());
        }
        return found;
    }

    @ParameterizedTest
    @MethodSource("batches")
    void aSpoolKeepsEverythingItAccepted(List<LogRecord> batch) {
        Spool spool = fresh().build();
        batch.forEach(spool::append);
        spool.sealAll();
        assertThat(everything(spool.directory()))
                .extracting(item -> item.get("message"))
                .containsExactlyElementsOf(
                        batch.stream().map(LogRecord::message).toList());
    }

    @ParameterizedTest
    @MethodSource("batches")
    void aSpoolNeverReportsMoreThanItHolds(List<LogRecord> batch) {
        Spool spool = fresh().build();
        batch.forEach(spool::append);
        spool.sealAll();
        long onDisk = Segments.listSegments(spool.directory()).stream()
                .mapToLong(SegmentInfo::size)
                .sum();
        assertThat(spool.usage().used()).isEqualTo(onDisk);
    }

    @ParameterizedTest
    @MethodSource("longBatches")
    void aSpoolStaysInsideItsBudget(List<LogRecord> batch) {
        Budget budget = new Budget(64 * 1024);
        Spool spool = Spool.builder(tmp.resolve("spool"), budget)
                .segmentBytes(8192)
                .compress(false)
                .build();
        for (LogRecord record : batch) {
            try {
                spool.append(record);
            } catch (SpoolError refused) {
                // A refusal is a legitimate outcome. What is not legitimate is accepting a write
                // that puts the spool over its limit.
                break;
            }
        }
        spool.sealAll();
        assertThat(spool.usage().used()).isLessThanOrEqualTo(budget.limitBytes());
    }

    @ParameterizedTest
    @MethodSource("shortBatches")
    void aRecordLandsInItsOwnClass(List<LogRecord> batch) {
        Spool spool = fresh().table(TABLE).build();
        for (LogRecord record : batch) {
            assertThat(spool.append(record).klass())
                    .isEqualTo(TABLE.classFor(record.unit()).name());
        }
        spool.sealAll();
    }

    @ParameterizedTest
    @MethodSource("shortBatches")
    void aSegmentOnlyEverHoldsOneClass(List<LogRecord> batch) {
        // This is what makes eviction safe: a segment is deleted whole, so one that mixed payment
        // records with chatter could not be deleted at all.
        Spool spool = fresh().table(TABLE).segmentBytes(1024).build();
        batch.forEach(spool::append);
        spool.sealAll();
        for (SegmentInfo info : Segments.listSegments(spool.directory())) {
            Set<String> classes = new HashSet<>();
            for (Map<String, Object> item :
                    SegmentReader.readSegment(info.path()).payloads()) {
                classes.add(TABLE.classFor(String.valueOf(item.get("unit"))).name());
            }
            assertThat(classes).isSubsetOf(Set.of(info.klass()));
        }
    }

    @ParameterizedTest
    @MethodSource("shortBatches")
    void reopeningASpoolFindsWhatTheLastOneLeft(List<LogRecord> batch) {
        Spool spool = fresh().build();
        batch.forEach(spool::append);
        spool.detach();
        Spool again = fresh().build();
        again.open();
        assertThat(everything(again.directory()))
                .extracting(item -> item.get("message"))
                .containsExactlyElementsOf(
                        batch.stream().map(LogRecord::message).toList());
    }

    static java.util.stream.Stream<List<Long>> sizeLists() {
        return Samples.drawn(131, random -> {
            int count = random.nextInt(21);
            List<Long> out = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                out.add(1L + random.nextInt(4096));
            }
            return out;
        });
    }

    @ParameterizedTest
    @MethodSource("sizeLists")
    void usageIsTheSumOfWhatItWasGiven(List<Long> sizes) {
        List<SegmentInfo> segments = new ArrayList<>();
        for (int index = 0; index < sizes.size(); index++) {
            segments.add(new SegmentInfo(
                    Path.of(String.format("%012d-standard.spool", index)), index, "standard", sizes.get(index)));
        }
        Usage usage = Usage.measure(segments, 0);
        assertThat(usage.used())
                .isEqualTo(sizes.stream().mapToLong(Long::longValue).sum());
        assertThat(usage.segments()).isEqualTo(sizes.size());
    }

    @ParameterizedTest
    @ValueSource(longs = {1024, 65536, 1_048_576})
    void aBudgetAdmitsNothingLargerThanItself(long limit) {
        Budget budget = new Budget(limit);
        assertThat(budget.admits(Usage.measure(List.of(), 0), limit + 1)).isFalse();
    }
}
