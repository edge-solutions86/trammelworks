package io.github.rkaushikdev.trammelworks.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.Fragment;
import io.github.rkaushikdev.trammelworks.IngestError;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestTailTest {

    static final String TS = "2025-03-04T11:30:00.123456789Z";

    @TempDir
    Path tmp;

    Path logfile;

    @BeforeEach
    void setUp() throws IOException {
        logfile = Files.createFile(tmp.resolve("0.log"));
    }

    static String cri(String message) {
        return TS + " stdout F " + message + "\n";
    }

    static void append(Path path, String text) {
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    static void empty(Path path) throws IOException {
        Files.write(path, new byte[0]);
    }

    static void rotate(Path path) throws IOException {
        Files.move(path, path.resolveSibling("0.log.1"));
        Files.createFile(path);
    }

    static Tailer tailer(Path path) {
        return new Tailer(path, LineReader.cri());
    }

    static List<String> messages(PollResult result) {
        return result.fragments().stream().map(Fragment::message).toList();
    }

    @Test
    void anEmptyFileYieldsNothing() {
        PollResult result = tailer(logfile).poll();
        assertThat(result.fragments()).isEmpty();
        assertThat(result.event()).isSameAs(TailEvent.NONE);
        assertThat(result.empty()).isTrue();
    }

    @Test
    void aMissingFileThatWasNeverSeenIsQuiet() {
        assertThat(tailer(tmp.resolve("absent.log")).poll().event()).isSameAs(TailEvent.NONE);
    }

    @Test
    void theFirstPollReadsWhatIsThere() {
        append(logfile, cri("one") + cri("two"));
        PollResult result = tailer(logfile).poll();
        assertThat(messages(result)).containsExactly("one", "two");
        assertThat(result.event()).isSameAs(TailEvent.GROWN);
    }

    @Test
    void aSecondPollReadsOnlyWhatIsNew() {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one"));
        tail.poll();
        append(logfile, cri("two"));
        assertThat(messages(tail.poll())).containsExactly("two");
    }

    @Test
    void aPollWithNoChangeReadsNothing() {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one"));
        tail.poll();
        assertThat(tail.poll().empty()).isTrue();
    }

    @Test
    void theOffsetAdvancesByWhatWasRead() {
        Tailer tail = tailer(logfile);
        String text = cri("one");
        append(logfile, text);
        tail.poll();
        assertThat(tail.offset()).isEqualTo(text.length());
    }

    @Test
    void aHalfWrittenLineIsHeldBack() {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one") + TS + " stdout F unfin");
        assertThat(messages(tail.poll())).containsExactly("one");
        assertThat(tail.buffered()).isPositive();
    }

    @Test
    void theHeldBytesJoinTheNextRead() {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one") + TS + " stdout F unfin");
        tail.poll();
        append(logfile, "ished\n");
        assertThat(messages(tail.poll())).containsExactly("unfinished");
        assertThat(tail.buffered()).isZero();
    }

    @Test
    void offsetsAreAbsoluteAcrossPolls() {
        Tailer tail = tailer(logfile);
        String first = cri("one");
        append(logfile, first);
        tail.poll();
        append(logfile, cri("two"));
        assertThat(tail.poll().fragments().get(0).source().offset()).isEqualTo(first.length());
    }

    @Test
    void anOffsetIsRightEvenWhenALineSpannedTwoPolls() {
        Tailer tail = tailer(logfile);
        append(logfile, TS + " stdout F split");
        tail.poll();
        append(logfile, "line\n");
        assertThat(tail.poll().fragments().get(0).source().offset()).isZero();
    }

    @Test
    void aSmallChunkSizeNeedsSeveralPolls() {
        Tailer tail = new Tailer(logfile, LineReader.cri(), 16);
        append(logfile, cri("one") + cri("two"));
        List<String> seen = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            seen.addAll(messages(tail.poll()));
        }
        assertThat(seen).containsExactly("one", "two");
    }

    @Test
    void aNonPositiveChunkSizeIsRefused() {
        assertThatThrownBy(() -> new Tailer(logfile, LineReader.cri(), 0))
                .isInstanceOf(IngestError.class)
                .hasMessageContaining("chunk_size must be positive");
    }

    @Test
    void truncationInPlaceIsNoticed() throws IOException {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one") + cri("two"));
        tail.poll();
        empty(logfile);
        append(logfile, cri("fresh"));
        PollResult result = tail.poll();
        assertThat(result.event()).isSameAs(TailEvent.TRUNCATED);
        assertThat(messages(result)).containsExactly("fresh");
    }

    @Test
    void truncationResetsTheOffset() throws IOException {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one"));
        tail.poll();
        empty(logfile);
        tail.poll();
        assertThat(tail.offset()).isZero();
    }

    @Test
    void truncationReportsTheBytesItHadBuffered() throws IOException {
        Tailer tail = tailer(logfile);
        append(logfile, TS + " stdout F half");
        tail.poll();
        assertThat(tail.buffered()).isPositive();
        empty(logfile);
        assertThat(tail.poll().lostBytes()).isPositive();
    }

    @Test
    void aRenameRotationIsNoticed() throws IOException {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one"));
        tail.poll();
        rotate(logfile);
        append(logfile, cri("after"));
        PollResult result = tail.poll();
        assertThat(result.event()).isSameAs(TailEvent.ROTATED);
        assertThat(messages(result)).containsExactly("after");
    }

    @Test
    void aRotationStartsTheNewFileAtZero() throws IOException {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one") + cri("two") + cri("three"));
        tail.poll();
        rotate(logfile);
        append(logfile, cri("after"));
        assertThat(tail.poll().fragments().get(0).source().offset()).isZero();
    }

    @Test
    void aDeletedFileIsReportedOnce() throws IOException {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one"));
        tail.poll();
        Files.delete(logfile);
        assertThat(tail.poll().event()).isSameAs(TailEvent.MISSING);
        assertThat(tail.poll().event()).isSameAs(TailEvent.NONE);
    }

    @Test
    void aFileThatComesBackIsReadFromTheStart() throws IOException {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one"));
        tail.poll();
        Files.delete(logfile);
        tail.poll();
        Files.createFile(logfile);
        append(logfile, cri("again"));
        assertThat(messages(tail.poll())).containsExactly("again");
    }

    @Test
    void seekToResumesFromACheckpoint() throws IOException {
        String first = cri("one");
        append(logfile, first + cri("two"));
        Tailer tail = tailer(logfile);
        tail.seekTo(first.length(), FileIdentity.of(logfile));
        assertThat(messages(tail.poll())).containsExactly("two");
    }

    @Test
    void seekToRefusesANegativeOffset() {
        assertThatThrownBy(() -> tailer(logfile).seekTo(-1))
                .isInstanceOf(IngestError.class)
                .hasMessageContaining("negative offset");
    }

    @Test
    void seekToClearsAnyHeldBytes() {
        Tailer tail = tailer(logfile);
        append(logfile, TS + " stdout F half");
        tail.poll();
        tail.seekTo(0);
        assertThat(tail.buffered()).isZero();
    }

    @Test
    void fileIdentityOfTheSameFileMatches() throws IOException {
        assertThat(FileIdentity.of(logfile)).isEqualTo(FileIdentity.of(logfile));
    }

    @Test
    void fileIdentityDiffersBetweenTwoFiles() throws IOException {
        Path left = Files.createFile(tmp.resolve("a.log"));
        Path right = Files.createFile(tmp.resolve("b.log"));
        assertThat(FileIdentity.of(left)).isNotEqualTo(FileIdentity.of(right));
    }

    @Test
    void aBadLineIsCountedAndSkipped() {
        append(logfile, cri("one") + "garbage\n" + cri("three"));
        PollResult result = tailer(logfile).poll();
        assertThat(messages(result)).containsExactly("one", "three");
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
    }

    @Test
    void aRewritePastTheOldOffsetIsStillATruncation() throws IOException {
        Tailer tail = tailer(logfile);
        append(logfile, cri("first shift line one") + cri("first shift line two"));
        tail.poll();
        empty(logfile);
        append(logfile, cri("second shift after copytruncate and a busy container"));
        PollResult result = tail.poll();
        assertThat(result.event()).isSameAs(TailEvent.TRUNCATED);
        assertThat(messages(result)).containsExactly("second shift after copytruncate and a busy container");
    }

    @Test
    void aPlainAppendIsNotMistakenForARewrite() {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one"));
        tail.poll();
        append(logfile, cri("two"));
        assertThat(tail.poll().event()).isSameAs(TailEvent.GROWN);
    }

    @Test
    void theSignatureIsTakenFromTheFrontOfTheFile() {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one"));
        tail.poll();
        byte[] expected = cri("one").getBytes(StandardCharsets.UTF_8);
        assertThat(tail.signature()).isEqualTo(Arrays.copyOf(expected, Math.min(64, expected.length)));
    }

    @Test
    void theSignatureGrowsUntilItIsAFullWindow() {
        Tailer tail = tailer(logfile);
        append(logfile, "short\n");
        tail.poll();
        assertThat(tail.signature()).hasSize(6);
        append(logfile, cri("a line long enough to fill the rest of the window"));
        tail.poll();
        assertThat(tail.signature()).hasSize(64);
    }

    @Test
    void aGrownFileStillStartsWithItsSignature() {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one"));
        tail.poll();
        byte[] first = tail.signature();
        append(logfile, cri("two"));
        tail.poll();
        assertThat(Arrays.copyOf(tail.signature(), first.length)).isEqualTo(first);
    }

    @Test
    void theSignatureIsClearedByARotation() throws IOException {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one"));
        tail.poll();
        rotate(logfile);
        tail.poll();
        assertThat(tail.signature()).isEmpty();
    }

    @Test
    void theIdentitySurvivesATruncation() throws IOException {
        Tailer tail = tailer(logfile);
        append(logfile, cri("one"));
        tail.poll();
        empty(logfile);
        tail.poll();
        assertThat(tail.identity()).contains(FileIdentity.of(logfile));
    }

    @Test
    void seekToANonzeroOffsetCapturesASignature() {
        append(logfile, cri("one") + cri("two"));
        Tailer tail = tailer(logfile);
        tail.seekTo(cri("one").length());
        assertThat(tail.signature()).isNotEmpty();
    }

    @Test
    void seekToZeroLeavesNoSignature() {
        append(logfile, cri("one"));
        Tailer tail = tailer(logfile);
        tail.seekTo(0);
        assertThat(tail.signature()).isEmpty();
    }
}
