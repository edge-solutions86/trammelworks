package io.github.rkaushikdev.trammelworks.spool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.SpoolError;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpoolReaderTest {

    static final byte[] ONE = Frames.encodeFrame(Map.of("n", 1), false);
    static final byte[] TWO = Frames.encodeFrame(Map.of("n", 2), false);

    @TempDir
    Path tmp;

    Path spool;

    @BeforeEach
    void setUp() throws IOException {
        spool = Files.createDirectories(tmp.resolve("spool"));
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    Path sealed(byte[] data) {
        return sealed(data, 0);
    }

    Path sealed(byte[] data, long sequence) {
        return write(spool.resolve(Segments.segmentName(sequence, "payment")), data);
    }

    Path partial(byte[] data) {
        return partial(data, 0);
    }

    Path partial(byte[] data, long sequence) {
        return write(spool.resolve(Segments.segmentName(sequence, "payment") + ".part"), data);
    }

    static Path write(Path path, byte[] data) {
        try {
            return Files.write(path, data);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    @Test
    void aWholeSegmentReadsBack() {
        SegmentRead found = SegmentReader.readSegment(sealed(concat(ONE, TWO)));
        assertThat(found.frames()).extracting(frame -> frame.payload().get("n")).containsExactly(1L, 2L);
        assertThat(found.whole()).isTrue();
    }

    @Test
    void thePayloadsComeOutInOrder() {
        assertThat(SegmentReader.readSegment(sealed(concat(ONE, TWO))).payloads())
                .isEqualTo(List.of(Map.of("n", 1L), Map.of("n", 2L)));
    }

    @Test
    void anEmptySegmentReadsAsNothing() {
        SegmentRead found = SegmentReader.readSegment(sealed(new byte[0]));
        assertThat(found.frames()).isEmpty();
        assertThat(found.whole()).isTrue();
    }

    @Test
    void aTornTailIsReported() {
        SegmentRead found = SegmentReader.readSegment(sealed(concat(ONE, Arrays.copyOf(TWO, 5))));
        assertThat(found.frames()).hasSize(1);
        assertThat(found.whole()).isFalse();
        assertThat(found.consumed()).isEqualTo(ONE.length);
    }

    @Test
    void aCorruptFrameStopsTheRead() {
        byte[] broken = TWO.clone();
        broken[Frames.HEADER_SIZE] ^= (byte) 0xFF;
        SegmentRead found = SegmentReader.readSegment(sealed(concat(ONE, broken)));
        assertThat(found.frames()).hasSize(1);
        assertThat(found.note()).isPresent();
    }

    @Test
    void anUnreadablePathIsRefused() {
        assertThatThrownBy(() -> SegmentReader.readSegment(spool.resolve("absent.spool")))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("segment unreadable");
    }

    @Test
    void recoveringAWholePartialSealsIt() {
        Path path = partial(concat(ONE, TWO));
        Recovery found = SegmentReader.recoverPartial(path);
        assertThat(found.sealed()).isPresent();
        assertThat(found.frames()).isEqualTo(2);
        assertThat(found.droppedBytes()).isZero();
        assertThat(path).doesNotExist();
    }

    @Test
    void theSealedSegmentIsThenListed() {
        SegmentReader.recoverPartial(partial(ONE));
        assertThat(Segments.listSegments(spool))
                .extracting(SegmentInfo::sequence)
                .containsExactly(0L);
    }

    @Test
    void aTornPartialIsTruncatedToTheLastGoodFrame() throws IOException {
        Recovery found = SegmentReader.recoverPartial(partial(concat(ONE, Arrays.copyOf(TWO, 5))));
        assertThat(found.frames()).isEqualTo(1);
        assertThat(found.droppedBytes()).isEqualTo(5);
        assertThat(found.sealed()).isPresent();
        assertThat(Files.readAllBytes(found.sealed().get().path())).isEqualTo(ONE);
    }

    @Test
    void theRecordsBeforeTheTearSurvive() {
        SegmentReader.recoverPartial(partial(concat(ONE, Arrays.copyOf(TWO, 5))));
        SegmentRead kept =
                SegmentReader.readSegment(Segments.listSegments(spool).get(0).path());
        assertThat(kept.payloads()).isEqualTo(List.of(Map.of("n", 1L)));
        assertThat(kept.whole()).isTrue();
    }

    @Test
    void aPartialWithNoWholeFrameIsRemoved() {
        Path path = partial(Arrays.copyOf(ONE, 4));
        Recovery found = SegmentReader.recoverPartial(path);
        assertThat(found.removed()).isTrue();
        assertThat(found.sealed()).isEmpty();
        assertThat(path).doesNotExist();
        assertThat(Segments.listSegments(spool)).isEmpty();
    }

    @Test
    void anEmptyPartialIsRemoved() {
        assertThat(SegmentReader.recoverPartial(partial(new byte[0])).removed()).isTrue();
    }

    @Test
    void recoveringAFileThatIsNotAPartialIsRefused() {
        assertThatThrownBy(() -> SegmentReader.recoverPartial(sealed(ONE)))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("not a partial segment");
    }

    @Test
    void aPartialWithAJunkNameIsRefused() {
        Path path = write(spool.resolve("notes.part"), ONE);
        assertThatThrownBy(() -> SegmentReader.recoverPartial(path))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("unreadable name");
    }

    @Test
    void recoveringADirectoryHandlesEveryPartial() {
        partial(ONE, 0);
        partial(concat(ONE, TWO), 1);
        assertThat(SegmentReader.recoverDirectory(spool)).hasSize(2);
        assertThat(Segments.listSegments(spool))
                .extracting(SegmentInfo::sequence)
                .containsExactly(0L, 1L);
    }

    @Test
    void recoveringADirectoryLeavesSealedSegmentsAlone() {
        sealed(ONE, 0);
        partial(TWO, 1);
        assertThat(SegmentReader.recoverDirectory(spool)).hasSize(1);
        assertThat(Segments.listSegments(spool)).hasSize(2);
    }

    @Test
    void recoveringAnAbsentDirectoryIsEmpty() {
        assertThat(SegmentReader.recoverDirectory(tmp.resolve("nope"))).isEmpty();
    }

    @Test
    void recoveringACleanDirectoryDoesNothing() {
        sealed(ONE);
        assertThat(SegmentReader.recoverDirectory(spool)).isEmpty();
    }

    @Test
    void quarantineMovesTheFileAside() throws IOException {
        Path path = sealed(ONE);
        Path moved = SegmentReader.quarantine(path);
        assertThat(moved.getFileName().toString()).endsWith(SegmentReader.QUARANTINE_SUFFIX);
        assertThat(path).doesNotExist();
        assertThat(Files.readAllBytes(moved)).isEqualTo(ONE);
    }

    @Test
    void aQuarantinedFileIsNoLongerASegment() {
        SegmentReader.quarantine(sealed(ONE));
        assertThat(Segments.listSegments(spool)).isEmpty();
    }

    @Test
    void quarantiningTwiceReplacesTheFirst() throws IOException {
        SegmentReader.quarantine(sealed(ONE));
        SegmentReader.quarantine(sealed(TWO));
        List<Path> moved;
        try (Stream<Path> found = Files.list(spool)) {
            moved = found.filter(path -> path.toString().endsWith(SegmentReader.QUARANTINE_SUFFIX))
                    .toList();
        }
        assertThat(moved).hasSize(1);
        assertThat(Files.readAllBytes(moved.get(0))).isEqualTo(TWO);
    }
}
