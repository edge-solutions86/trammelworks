package io.github.rkaushikdev.trammelworks.spool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.SpoolError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpoolSegmentTest {

    static final byte[] FRAME = Frames.encodeFrame(Map.of("message", "nozzle 1 lifted"));

    @TempDir
    Path tmp;

    Path spool;

    @BeforeEach
    void setUp() {
        spool = tmp.resolve("spool");
    }

    SegmentWriter writer() {
        return writer(0, "payment");
    }

    SegmentWriter writer(long sequence, String klass) {
        return new SegmentWriter(spool, sequence, klass);
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment", "tank_gauge", "a1"})
    void checkClassAcceptsASaneName(String klass) {
        assertThat(Segments.checkClass(klass)).isEqualTo(klass);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Payment", "1st", "with-dash", "", "with space"})
    void checkClassRejectsAnythingElse(String klass) {
        assertThatThrownBy(() -> Segments.checkClass(klass))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("malformed priority class");
    }

    @Test
    void aNameIsZeroPaddedSoItSorts() {
        assertThat(Segments.segmentName(41, "payment")).isEqualTo("000000000041-payment.spool");
    }

    @Test
    void namesSortLexicallyInSequenceOrder() {
        List<String> names = new ArrayList<>();
        for (long number : new long[] {2, 10, 1}) {
            names.add(Segments.segmentName(number, "payment"));
        }
        names.sort(null);
        assertThat(names)
                .containsExactly(
                        Segments.segmentName(1, "payment"),
                        Segments.segmentName(2, "payment"),
                        Segments.segmentName(10, "payment"));
    }

    @Test
    void aNegativeSequenceIsRefused() {
        assertThatThrownBy(() -> Segments.segmentName(-1, "payment"))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("negative segment sequence");
    }

    @Test
    void aNameRoundTrips() {
        assertThat(Segments.parseSegmentName(Segments.segmentName(41, "payment")))
                .contains(new Segments.Name(41, "payment"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"41-payment.spool", "000000000041-payment.part", "notes.txt", ""})
    void anUnrelatedNameIsNotASegment(String name) {
        assertThat(Segments.parseSegmentName(name)).isEmpty();
    }

    @Test
    void listingAnAbsentDirectoryIsEmpty() {
        assertThat(Segments.listSegments(spool)).isEmpty();
    }

    @Test
    void aSealedSegmentIsListed() {
        SegmentWriter handle = writer();
        handle.append(FRAME);
        handle.seal();
        List<SegmentInfo> found = Segments.listSegments(spool);
        assertThat(found).extracting(SegmentInfo::sequence).containsExactly(0L);
        assertThat(found.get(0).size()).isEqualTo(FRAME.length);
    }

    @Test
    void segmentsAreListedOldestFirst() {
        for (long sequence : new long[] {2, 0, 1}) {
            SegmentWriter handle = writer(sequence, "payment");
            handle.append(FRAME);
            handle.seal();
        }
        assertThat(Segments.listSegments(spool))
                .extracting(SegmentInfo::sequence)
                .containsExactly(0L, 1L, 2L);
    }

    @Test
    void anUnsealedSegmentIsNotListed() {
        SegmentWriter handle = writer();
        handle.append(FRAME);
        handle.flush();
        assertThat(Segments.listSegments(spool)).isEmpty();
    }

    @Test
    void anUnrelatedFileIsNotListed() throws IOException {
        Files.createDirectories(spool);
        Files.writeString(spool.resolve("notes.txt"), "hello");
        assertThat(Segments.listSegments(spool)).isEmpty();
    }

    @Test
    void theClassIsReadBackFromTheName() {
        SegmentWriter handle = writer(0, "tank_gauge");
        handle.append(FRAME);
        handle.seal();
        assertThat(Segments.listSegments(spool).get(0).klass()).isEqualTo("tank_gauge");
    }

    @Test
    void theFirstSequenceOnAnEmptyDirectoryIsZero() {
        assertThat(Segments.nextSequence(spool)).isZero();
    }

    @Test
    void theNextSequenceIsOnePastTheHighest() {
        SegmentWriter handle = writer(7, "payment");
        handle.append(FRAME);
        handle.seal();
        assertThat(Segments.nextSequence(spool)).isEqualTo(8);
    }

    @Test
    void anUnsealedSegmentStillReservesItsNumber() {
        SegmentWriter handle = writer(7, "payment");
        handle.append(FRAME);
        handle.flush();
        assertThat(Segments.nextSequence(spool)).isEqualTo(8);
    }

    @Test
    void writingCreatesTheDirectory() {
        writer().append(FRAME);
        assertThat(spool).isDirectory();
    }

    @Test
    void theWriteGoesToAPartFile() {
        SegmentWriter handle = writer();
        handle.append(FRAME);
        assertThat(handle.partialPath()).exists();
        assertThat(handle.sealedPath()).doesNotExist();
    }

    @Test
    void sealingRenamesIntoPlace() {
        SegmentWriter handle = writer();
        handle.append(FRAME);
        Optional<SegmentInfo> sealed = handle.seal();
        assertThat(sealed).isPresent();
        assertThat(sealed.get().path()).exists();
        assertThat(handle.partialPath()).doesNotExist();
    }

    @Test
    void theOffsetReturnedIsWhereTheFrameLanded() {
        SegmentWriter handle = writer();
        assertThat(handle.append(FRAME)).isZero();
        assertThat(handle.append(FRAME)).isEqualTo(FRAME.length);
    }

    @Test
    void theSizeFollowsWhatWasWritten() {
        SegmentWriter handle = writer();
        handle.append(FRAME);
        handle.append(FRAME);
        assertThat(handle.size()).isEqualTo(2L * FRAME.length);
        assertThat(handle.frames()).isEqualTo(2);
    }

    @Test
    void anEmptySegmentIsRemovedRatherThanSealed() {
        SegmentWriter handle = writer();
        handle.stream();
        assertThat(handle.seal()).isEmpty();
        assertThat(Segments.listSegments(spool)).isEmpty();
    }

    @Test
    void sealingASegmentThatWasNeverOpenedIsANoOp() {
        assertThat(writer().seal()).isEmpty();
    }

    @Test
    void fullFollowsTheSizeCap() {
        SegmentWriter handle = new SegmentWriter(spool, 0, "payment", FRAME.length, 0);
        assertThat(handle.full()).isFalse();
        handle.append(FRAME);
        assertThat(handle.full()).isTrue();
    }

    @Test
    void wouldOverflowAsksBeforeWriting() {
        SegmentWriter handle = new SegmentWriter(spool, 0, "payment", FRAME.length + 1L, 0);
        assertThat(handle.wouldOverflow(FRAME.length)).isFalse();
        handle.append(FRAME);
        assertThat(handle.wouldOverflow(FRAME.length)).isTrue();
    }

    @Test
    void aNonPositiveCapIsRefused() {
        assertThatThrownBy(() -> new SegmentWriter(spool, 0, "payment", 0, 0))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("max_bytes must be positive");
    }

    @Test
    void aNegativeSyncIntervalIsRefused() {
        assertThatThrownBy(() -> new SegmentWriter(spool, 0, "payment", Segments.DEFAULT_SEGMENT_BYTES, -1))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("sync_every cannot be negative");
    }

    @Test
    void syncingEveryWriteLeavesTheBytesOnDisk() throws IOException {
        SegmentWriter handle = new SegmentWriter(spool, 0, "payment", Segments.DEFAULT_SEGMENT_BYTES, 1);
        handle.append(FRAME);
        assertThat(Files.size(handle.partialPath())).isEqualTo(FRAME.length);
    }

    @Test
    void theBytesAreReadableAfterSealing() throws IOException {
        SegmentWriter handle = writer();
        handle.append(FRAME);
        Optional<SegmentInfo> sealed = handle.seal();
        assertThat(sealed).isPresent();
        assertThat(Files.readAllBytes(sealed.get().path())).isEqualTo(FRAME);
    }

    @Test
    void reopeningTheSameSequenceAppends() {
        SegmentWriter first = writer();
        first.append(FRAME);
        first.close();
        SegmentWriter second = writer();
        second.append(FRAME);
        assertThat(second.size()).isEqualTo(2L * FRAME.length);
    }

    @Test
    void openReportsWhetherAHandleIsHeld() {
        SegmentWriter handle = writer();
        assertThat(handle.isOpen()).isFalse();
        handle.append(FRAME);
        assertThat(handle.isOpen()).isTrue();
        handle.seal();
        assertThat(handle.isOpen()).isFalse();
    }
}
