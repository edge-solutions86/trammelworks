package io.github.rkaushikdev.trammelworks.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.SpoolError;
import io.github.rkaushikdev.trammelworks.spool.Frames;
import io.github.rkaushikdev.trammelworks.spool.SegmentInfo;
import io.github.rkaushikdev.trammelworks.spool.SegmentReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShipCompactTest {

    @TempDir
    Path tmp;

    SegmentInfo segment;

    @BeforeEach
    void setUp() {
        segment = writeSegment(tmp, 41, "payment", 20, 400);
    }

    static Map<String, Object> body(int index, int size) {
        return Maps.of("seq", index, "message", index + "-" + "x".repeat(size));
    }

    static SegmentInfo writeSegment(Path directory, long sequence, String klass, int count, int size) {
        try {
            Files.createDirectories(directory);
            Path path = directory.resolve(String.format("%012d-%s.spool", sequence, klass));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int index = 0; index < count; index++) {
                out.writeBytes(Frames.encodeFrame(body(index, size), false));
            }
            Files.write(path, out.toByteArray());
            return new SegmentInfo(path, sequence, klass, Files.size(path));
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    static AckLedger ledgerFor(SegmentInfo segment, int acked) {
        AckLedger built = new AckLedger();
        built.mark(segment.name(), 20);
        if (acked != 0) {
            built.acknowledge(segment.name(), acked);
        }
        return built;
    }

    static List<Long> sequences(Path path) {
        return SegmentReader.readSegment(path).frames().stream()
                .map(frame -> Coerce.toLong(frame.payload().get("seq")))
                .toList();
    }

    byte[] data() {
        try {
            return Files.readAllBytes(segment.path());
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    void tear(int bytes) throws IOException {
        byte[] data = data();
        Files.write(segment.path(), Arrays.copyOf(data, data.length - bytes));
    }

    @Test
    void thePrefixOfNoFramesIsNoBytes() {
        assertThat(Compaction.prefixBytes(data(), 0)).isZero();
    }

    @Test
    void thePrefixOfEveryFrameIsTheWholeFile() {
        byte[] data = data();
        assertThat(Compaction.prefixBytes(data, 20)).isEqualTo(data.length);
    }

    @Test
    void thePrefixGrowsWithTheCount() {
        byte[] data = data();
        assertThat(Compaction.prefixBytes(data, 5)).isLessThan(Compaction.prefixBytes(data, 6));
    }

    @Test
    void aPrefixPastTheEndIsRefused() {
        assertThatThrownBy(() -> Compaction.prefixBytes(data(), 21))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("more frames than the segment holds");
    }

    @Test
    void aNegativePrefixIsRefused() {
        assertThatThrownBy(() -> Compaction.prefixBytes(data(), -1))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("negative");
    }

    @Test
    void aTornSegmentWillNotBeMeasured() {
        byte[] data = data();
        assertThatThrownBy(() -> Compaction.prefixBytes(Arrays.copyOf(data, data.length - 4), 2))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("will not read whole");
    }

    @Test
    void nothingAcknowledgedIsNotWorthCompacting() {
        assertThat(Compaction.planOne(segment, 0, 20)).isEmpty();
    }

    @Test
    void everythingAcknowledgedIsLeftForTheShipper() {
        assertThat(Compaction.planOne(segment, 20, 20)).isEmpty();
    }

    @Test
    void aPartShippedSegmentIsWorthCompacting() {
        CompactionPlan plan = Compaction.planOne(segment, 15, 20).orElseThrow();
        assertThat(plan.drop()).isEqualTo(15);
        assertThat(plan.keep()).isEqualTo(5);
        assertThat(plan.reclaim()).isPositive();
    }

    @Test
    void aSmallSavingIsNotWorthTheRewrite() {
        assertThat(Compaction.planOne(segment, 1, 20, 1024 * 1024, Compaction.DEFAULT_MIN_FRACTION))
                .isEmpty();
    }

    @Test
    void aLargeFractionIsWorthItEvenWhenSmall() {
        SegmentInfo built = writeSegment(tmp, 7, "payment", 4, 10);
        assertThat(Compaction.planOne(built, 3, 4, 1024 * 1024, Compaction.DEFAULT_MIN_FRACTION))
                .isPresent();
    }

    @Test
    void thePlanReadsAsOneLine() {
        assertThat(Compaction.planOne(segment, 10, 20).orElseThrow().toString()).contains("drop 10 keep 10");
    }

    @Test
    void thePlanKnowsItsFraction() {
        assertThat(Compaction.planOne(segment, 10, 20).orElseThrow().fraction()).isBetween(0.4, 0.6);
    }

    @Test
    void aMissingSegmentIsRefused() {
        SegmentInfo absent = new SegmentInfo(tmp.resolve("gone.spool"), 1, "payment", 10);
        assertThatThrownBy(() -> Compaction.planOne(absent, 1, 2))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("unreadable");
    }

    @Test
    void planningSkipsSegmentsWithNoWatermark() {
        assertThat(Compaction.planCompaction(List.of(segment), new AckLedger()).plans())
                .isEmpty();
    }

    @Test
    void planningSkipsAPinnedSegment() {
        Survey survey = Compaction.planCompaction(
                List.of(segment),
                ledgerFor(segment, 15),
                Compaction.DEFAULT_MIN_RECLAIM,
                Compaction.DEFAULT_MIN_FRACTION,
                Set.of(segment.path()));
        assertThat(survey.plans()).isEmpty();
    }

    @Test
    void planningPutsTheBiggestSavingFirst() {
        SegmentInfo small = writeSegment(tmp, 1, "payment", 20, 400);
        SegmentInfo large = writeSegment(tmp, 2, "payment", 20, 4000);
        AckLedger ledger = new AckLedger();
        for (SegmentInfo item : List.of(small, large)) {
            ledger.mark(item.name(), 20);
            ledger.acknowledge(item.name(), 10);
        }
        Survey survey = Compaction.planCompaction(List.of(small, large), ledger);
        assertThat(survey.plans()).extracting(CompactionPlan::name).containsExactly(large.name(), small.name());
        assertThat(survey.reclaim()).isPositive();
    }

    @Test
    void rewritingDropsTheAcknowledgedPrefix() {
        Compaction.rewrite(segment.path(), 15);
        assertThat(sequences(segment.path())).containsExactly(15L, 16L, 17L, 18L, 19L);
    }

    @Test
    void rewritingShrinksTheFile() throws IOException {
        long before = Files.size(segment.path());
        Compaction.rewrite(segment.path(), 15);
        assertThat(Files.size(segment.path())).isLessThan(before);
    }

    @Test
    void rewritingReturnsTheNewSize() throws IOException {
        long size = Compaction.rewrite(segment.path(), 15);
        assertThat(size).isEqualTo(Files.size(segment.path()));
    }

    @Test
    void rewritingKeepsTheFramesByteIdentical() {
        byte[] data = data();
        int cut = (int) Compaction.prefixBytes(data, 15);
        Compaction.rewrite(segment.path(), 15);
        assertThat(data()).isEqualTo(Arrays.copyOfRange(data, cut, data.length));
    }

    @Test
    void rewritingNothingLeavesTheFileAlone() {
        byte[] data = data();
        Compaction.rewrite(segment.path(), 0);
        assertThat(data()).isEqualTo(data);
    }

    @Test
    void rewritingEverythingLeavesAnEmptyFile() {
        Compaction.rewrite(segment.path(), 20);
        assertThat(data()).isEmpty();
    }

    @Test
    void theSidecarDoesNotSurviveARewrite() throws IOException {
        Compaction.rewrite(segment.path(), 5);
        try (Stream<Path> found = Files.list(tmp)) {
            assertThat(found.filter(path -> path.toString().endsWith(Compaction.COMPACT_SUFFIX)))
                    .isEmpty();
        }
    }

    @Test
    void aTornSegmentIsNotRewritten() throws IOException {
        tear(3);
        assertThatThrownBy(() -> Compaction.rewrite(segment.path(), 2))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("does not read");
    }

    @Test
    void aLeftoverSidecarIsCleared() throws IOException {
        Path stray = segment.path().resolveSibling(segment.path().getFileName() + Compaction.COMPACT_SUFFIX);
        Files.write(stray, "half a file".getBytes());
        assertThat(Compaction.clearSidecars(tmp)).containsExactly(stray);
        assertThat(stray).doesNotExist();
    }

    @Test
    void clearingLeavesTheSegmentsAlone() {
        Compaction.clearSidecars(tmp);
        assertThat(segment.path()).exists();
    }

    @Test
    void clearingADirectoryThatIsNotThereIsHarmless() {
        assertThat(Compaction.clearSidecars(tmp.resolve("absent"))).isEmpty();
    }

    @Test
    void aPassCompactsAndReports() {
        CompactionResult result = new Compactor(tmp, ledgerFor(segment, 15)).run(List.of(segment));
        assertThat(result.segments()).isEqualTo(1);
        assertThat(result.framesDropped()).isEqualTo(15);
        assertThat(result.reclaimed()).isPositive();
    }

    @Test
    void aPassMovesTheWatermarkBackToZero() {
        AckLedger ledger = ledgerFor(segment, 15);
        new Compactor(tmp, ledger).run(List.of(segment));
        Watermark mark = ledger.marks().get(segment.name());
        assertThat(mark.total()).isEqualTo(5);
        assertThat(mark.acked()).isZero();
    }

    @Test
    void whatIsLeftIsWhatWasNotAcknowledged() {
        new Compactor(tmp, ledgerFor(segment, 15)).run(List.of(segment));
        assertThat(sequences(segment.path())).containsExactly(15L, 16L, 17L, 18L, 19L);
    }

    @Test
    void aPassWithNothingToDoIsIdle() {
        assertThat(new Compactor(tmp, ledgerFor(segment, 0))
                        .run(List.of(segment))
                        .idle())
                .isTrue();
    }

    @Test
    void anUnreadableSegmentIsNamedNotRaised() throws IOException {
        Compactor compactor = new Compactor(tmp, ledgerFor(segment, 15));
        tear(3);
        CompactionResult result = compactor.run(List.of(segment));
        assertThat(result.failed()).containsExactly(segment.name());
        assertThat(result.segments()).isZero();
    }

    @Test
    void aFailureLeavesTheWatermarkAlone() throws IOException {
        AckLedger ledger = ledgerFor(segment, 15);
        Compactor compactor = new Compactor(tmp, ledger);
        tear(3);
        compactor.run(List.of(segment));
        assertThat(ledger.marks().get(segment.name()).acked()).isEqualTo(15);
    }

    @Test
    void theMetricsCountWhatWasFreed() {
        Compactor compactor = new Compactor(tmp, ledgerFor(segment, 15));
        compactor.run(List.of(segment));
        assertThat(compactor.metrics().snapshot().get("spool_compacted_segments"))
                .isEqualTo(1L);
        assertThat(compactor.metrics().snapshot().get("spool_compacted_bytes")).isPositive();
    }

    @Test
    void aResultReadsAsOneLine() {
        assertThat(new CompactionResult(2, 30, 4096, List.of()).toString())
                .isEqualTo("compacted 2 segments, dropped 30 records, freed 4096 bytes");
    }

    @Test
    void aResultWithFailuresSaysSo() {
        assertThat(new CompactionResult(0, 0, 0, List.of("000000000001-payment.spool")).toString())
                .contains("1 failed");
    }

    @Test
    void aCompactedSegmentCanBeShippedFromTheStart() {
        AckLedger ledger = ledgerFor(segment, 15);
        new Compactor(tmp, ledger).run(List.of(segment));
        int frames = SegmentReader.readSegment(segment.path()).frames().size();
        assertThat(ledger.mark(segment.name(), frames).outstanding()).isEqualTo(5);
    }

    @Test
    void aSegmentThatWillNotReadIsSurveyedNotRaised() throws IOException {
        AckLedger ledger = ledgerFor(segment, 15);
        tear(3);
        Survey survey = Compaction.planCompaction(List.of(segment), ledger);
        assertThat(survey.unreadable()).containsExactly(segment.name());
        assertThat(survey.plans()).isEmpty();
    }
}
