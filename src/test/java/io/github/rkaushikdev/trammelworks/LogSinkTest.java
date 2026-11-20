package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogSinkTest {

    @TempDir
    Path tmp;

    RotatingSink sink() {
        return new RotatingSink(tmp.resolve("logs").resolve("stanchion.jsonl"));
    }

    RotatingSink sink(long maxBytes) {
        return new RotatingSink(tmp.resolve("logs").resolve("stanchion.jsonl"), maxBytes);
    }

    static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void aTinyCapIsRefused() {
        assertThatThrownBy(() -> sink(100)).isInstanceOf(StanchionError.class).hasMessageContaining("below 1 KiB");
    }

    @Test
    void writingCreatesTheDirectory() {
        RotatingSink built = sink();
        built.write("one\n");
        built.close();
        assertThat(built.path()).exists();
    }

    @Test
    void whatWasWrittenIsThere() throws IOException {
        RotatingSink built = sink();
        built.write("one\n");
        built.write("two\n");
        built.close();
        assertThat(read(built.path()).lines()).containsExactly("one", "two");
    }

    @Test
    void theSizeFollowsWhatWasWritten() {
        RotatingSink built = sink();
        built.write("one\n");
        assertThat(built.size()).isEqualTo(4);
        built.close();
    }

    @Test
    void aWriteThatWouldOverflowRotatesFirst() throws IOException {
        RotatingSink built = sink(1024);
        built.write("x".repeat(1019) + "\n");
        built.write("after\n");
        built.close();
        assertThat(built.rotations()).isEqualTo(1);
        assertThat(read(built.path())).isEqualTo("after\n");
    }

    @Test
    void thePreviousFileKeepsWhatWasRotated() throws IOException {
        RotatingSink built = sink(1024);
        built.write("x".repeat(1019) + "\n");
        built.write("after\n");
        built.close();
        assertThat(read(built.previousPath())).startsWith("x");
    }

    @Test
    void onlyOnePreviousFileIsKept() throws IOException {
        RotatingSink built = sink(1024);
        for (int index = 0; index < 6; index++) {
            built.write(index + "x".repeat(1018) + "\n");
        }
        built.close();
        try (Stream<Path> found = Files.list(tmp.resolve("logs"))) {
            assertThat(found.count()).isEqualTo(2);
        }
    }

    @Test
    void theNewestRotationWins() throws IOException {
        RotatingSink built = sink(1024);
        built.write("first" + "x".repeat(1014) + "\n");
        built.write("second" + "x".repeat(1013) + "\n");
        built.write("third\n");
        built.close();
        assertThat(read(built.previousPath())).startsWith("second");
    }

    @Test
    void theTotalCountsBothFiles() {
        RotatingSink built = sink(1024);
        built.write("x".repeat(1019) + "\n");
        built.write("after\n");
        built.flush();
        assertThat(built.totalBytes()).isGreaterThan(1000);
        built.close();
    }

    @Test
    void rotatingByHandMovesTheFile() {
        RotatingSink built = sink();
        built.write("one\n");
        built.rotate();
        assertThat(built.previousPath()).exists();
        assertThat(built.path()).doesNotExist();
        assertThat(built.size()).isZero();
    }

    @Test
    void rotatingAnEmptySinkIsHarmless() {
        RotatingSink built = sink();
        built.rotate();
        assertThat(built.rotations()).isEqualTo(1);
    }

    @Test
    void closingTwiceIsHarmless() {
        RotatingSink built = sink();
        built.write("one\n");
        built.close();
        assertThatCode(built::close).doesNotThrowAnyException();
    }

    @Test
    void reopeningAppends() throws IOException {
        RotatingSink first = sink();
        first.write("one\n");
        first.close();
        RotatingSink second = sink();
        second.write("two\n");
        second.close();
        assertThat(read(second.path()).lines()).containsExactly("one", "two");
    }

    @Test
    void anEventLogWritesThroughTheSink() throws IOException {
        RotatingSink built = sink();
        EventLog log = new EventLog(built, Level.DEBUG, new ManualClock());
        log.info("spool_opened", "segments", 3);
        built.close();
        Map<String, Object> payload = Json.loadsObject(read(built.path()).strip());
        assertThat(payload.get("event")).isEqualTo("spool_opened");
        assertThat(payload.get("segments")).isEqualTo(3L);
    }

    @Test
    void anEventLogRotatesWhenItFills() {
        RotatingSink built = sink(2048);
        EventLog log = new EventLog(built, Level.DEBUG, new ManualClock());
        for (int index = 0; index < 200; index++) {
            log.info("noise", "index", index);
        }
        built.close();
        assertThat(built.rotations()).isGreaterThanOrEqualTo(1);
        assertThat(built.totalBytes()).isLessThan(8192);
    }

    @Test
    void anEventLogUnderTheContainerRootIsRefused() {
        assertThatThrownBy(() -> RotatingSink.checkOutside(
                        Path.of("/var/log/containers/stanchion.jsonl"), "/var/log/containers"))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("must not live under the container log root");
    }

    @Test
    void aDeeperPathUnderTheRootIsAlsoRefused() {
        assertThatThrownBy(() -> RotatingSink.checkOutside(
                        Path.of("/var/log/containers/deep/stanchion.jsonl"), "/var/log/containers"))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("must not live under");
    }

    @Test
    void anEventLogElsewhereIsAllowed() {
        assertThatCode(() ->
                        RotatingSink.checkOutside(Path.of("/var/log/stanchion/stanchion.jsonl"), "/var/log/containers"))
                .doesNotThrowAnyException();
    }
}
