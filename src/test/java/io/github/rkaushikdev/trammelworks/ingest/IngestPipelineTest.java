package io.github.rkaushikdev.trammelworks.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogFiles;
import io.github.rkaushikdev.trammelworks.LogPaths;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.ManualClock;
import io.github.rkaushikdev.trammelworks.SiteTree;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestPipelineTest {

    static final String SITE = "US-TX-0417";
    static final String TS = "2025-03-04T11:30:00.123456789Z";

    @TempDir
    Path tmp;

    SiteTree tree;
    Path logfile;

    @BeforeEach
    void setUp() {
        tree = new SiteTree(tmp.resolve("log"));
        logfile = tree.writeCri("payment-kernel", List.of());
    }

    UnitPipeline.Builder builder(Path path) {
        return UnitPipeline.builder(SITE, LogPaths.parseLogPath(path), LineReader.cri())
                .clock(new ManualClock());
    }

    UnitPipeline build(Path path) {
        return builder(path).build();
    }

    CheckpointStore store() {
        return new CheckpointStore(tmp.resolve("ckpt"));
    }

    static void append(Path path, String text) {
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    static List<String> messages(List<LogRecord> records) {
        return records.stream().map(LogRecord::message).toList();
    }

    static Stitcher indent() {
        return new Stitcher(MultilineRule.continuing("payment-kernel", "^\\s+"));
    }

    @Test
    void aPollWithNothingNewGivesNothing() {
        assertThat(build(logfile).poll()).isEmpty();
    }

    @Test
    void oneLineBecomesOneRecord() {
        append(logfile, LogFiles.criFull(TS, "auth approved"));
        List<LogRecord> records = build(logfile).poll();
        assertThat(messages(records)).containsExactly("auth approved");
        assertThat(records.get(0).site()).isEqualTo(SITE);
        assertThat(records.get(0).unit()).isEqualTo("payment-kernel");
    }

    @Test
    void splitChunksBecomeOneRecord() {
        append(logfile, LogFiles.criSplit(TS, "a very long trace", 6));
        assertThat(messages(build(logfile).poll())).containsExactly("a very long trace");
    }

    @Test
    void aChunkStillOpenAtTheEndOfAPollWaits() {
        UnitPipeline pipeline = build(logfile);
        append(logfile, LogFiles.criLine(TS, "stdout", "P", "first half "));
        assertThat(pipeline.poll()).isEmpty();
        append(logfile, LogFiles.criLine(TS, "stdout", "F", "second half"));
        assertThat(messages(pipeline.poll())).containsExactly("first half second half");
    }

    @Test
    void theStatsFollowTheWork() {
        UnitPipeline pipeline = build(logfile);
        append(logfile, LogFiles.criFull(TS, "one") + LogFiles.criFull(TS, "two"));
        pipeline.poll();
        assertThat(pipeline.stats().polls()).isEqualTo(1);
        assertThat(pipeline.stats().fragments()).isEqualTo(2);
        assertThat(pipeline.stats().records()).isEqualTo(2);
    }

    @Test
    void aRotationFlushesWhatWasHeld() {
        UnitPipeline pipeline = build(logfile);
        append(logfile, LogFiles.criLine(TS, "stdout", "P", "unfinished when it rotated"));
        pipeline.poll();
        tree.rotate(logfile);
        append(logfile, LogFiles.criFull(TS, "after"));
        List<LogRecord> records = pipeline.poll();
        assertThat(messages(records)).containsExactly("unfinished when it rotated", "after");
        assertThat(records.get(0).labels().get("truncated")).isEqualTo("true");
        assertThat(pipeline.stats().rotations()).isEqualTo(1);
    }

    @Test
    void aTruncationFlushesAndIsCounted() throws IOException {
        UnitPipeline pipeline = build(logfile);
        append(logfile, LogFiles.criLine(TS, "stdout", "P", "open"));
        pipeline.poll();
        Files.write(logfile, new byte[0]);
        assertThat(messages(pipeline.poll())).containsExactly("open");
        assertThat(pipeline.stats().truncations()).isEqualTo(1);
        append(logfile, LogFiles.criFull(TS, "fresh"));
        assertThat(messages(pipeline.poll())).containsExactly("fresh");
    }

    @Test
    void aDisappearanceIsCounted() throws IOException {
        UnitPipeline pipeline = build(logfile);
        append(logfile, LogFiles.criFull(TS, "one"));
        pipeline.poll();
        Files.delete(logfile);
        pipeline.poll();
        assertThat(pipeline.stats().disappearances()).isEqualTo(1);
    }

    @Test
    void closeFlushesAnOpenLine() {
        UnitPipeline pipeline = build(logfile);
        append(logfile, LogFiles.criLine(TS, "stdout", "P", "never finished"));
        pipeline.poll();
        assertThat(messages(pipeline.close())).containsExactly("never finished");
    }

    @Test
    void closeOnACleanPipelineGivesNothing() {
        assertThat(build(logfile).close()).isEmpty();
    }

    @Test
    void theStitcherRunsAfterTheJoiner() {
        UnitPipeline pipeline = builder(logfile).stitcher(indent()).build();
        append(
                logfile,
                LogFiles.criFull(TS, "IllegalStateException")
                        + LogFiles.criFull(TS, "    at Pump.reset")
                        + LogFiles.criFull(TS, "plain line"));
        assertThat(messages(pipeline.poll())).containsExactly("IllegalStateException\n    at Pump.reset");
        assertThat(pipeline.close().get(0).message()).isEqualTo("plain line");
    }

    @Test
    void aSplitLineIsStitchedAsOneLineNotTwo() {
        UnitPipeline pipeline = builder(logfile).stitcher(indent()).build();
        append(logfile, LogFiles.criSplit(TS, "head    indented looking tail", 4));
        pipeline.poll();
        assertThat(messages(pipeline.close())).containsExactly("head    indented looking tail");
    }

    @Test
    void theKeyNamesTheSiteUnitAndAttempt() {
        assertThat(build(logfile).key()).isEqualTo("US-TX-0417_payment-kernel_0");
    }

    @Test
    void aCheckpointIsWrittenAfterAPoll() {
        CheckpointStore store = store();
        UnitPipeline pipeline = builder(logfile).checkpoints(store).build();
        String text = LogFiles.criFull(TS, "one");
        append(logfile, text);
        pipeline.poll();
        assertThat(store.load(pipeline.key()).orElseThrow().offset()).isEqualTo(text.length());
    }

    @Test
    void theCheckpointExcludesBytesThatAreNotYetALine() {
        CheckpointStore store = store();
        UnitPipeline pipeline = builder(logfile).checkpoints(store).build();
        String complete = LogFiles.criFull(TS, "one");
        append(logfile, complete + TS + " stdout F half");
        pipeline.poll();
        assertThat(store.load(pipeline.key()).orElseThrow().offset()).isEqualTo(complete.length());
    }

    @Test
    void resumeSeeksPastWhatWasAlreadyRead() {
        CheckpointStore store = store();
        UnitPipeline first = builder(logfile).checkpoints(store).build();
        append(logfile, LogFiles.criFull(TS, "one"));
        first.poll();
        append(logfile, LogFiles.criFull(TS, "two"));
        UnitPipeline second = builder(logfile).checkpoints(store).build();
        assertThat(second.resume()).isTrue();
        assertThat(messages(second.poll())).containsExactly("two");
    }

    @Test
    void resumeWithoutAStoreIsFalse() {
        assertThat(build(logfile).resume()).isFalse();
    }

    @Test
    void resumeWithNoStoredCheckpointIsFalse() {
        assertThat(builder(logfile).checkpoints(store()).build().resume()).isFalse();
    }

    @Test
    void resumeRefusesACheckpointFromADifferentFile() {
        CheckpointStore store = store();
        UnitPipeline pipeline = builder(logfile).checkpoints(store).build();
        append(logfile, LogFiles.criFull(TS, "one"));
        pipeline.poll();
        tree.rotate(logfile);
        append(logfile, LogFiles.criFull(TS, "after"));
        UnitPipeline fresh = builder(logfile).checkpoints(store).build();
        assertThat(fresh.resume()).isFalse();
        assertThat(messages(fresh.poll())).containsExactly("after");
    }

    @Test
    void resumeRefusesAnOffsetPastTheEnd() throws IOException {
        CheckpointStore store = store();
        UnitPipeline pipeline = builder(logfile).checkpoints(store).build();
        append(logfile, LogFiles.criFull(TS, "one") + LogFiles.criFull(TS, "two"));
        pipeline.poll();
        Files.write(logfile, new byte[0]);
        assertThat(builder(logfile).checkpoints(store).build().resume()).isFalse();
    }

    @Test
    void theCheckpointCarriesTheClockReading() {
        CheckpointStore store = store();
        ManualClock clock = new ManualClock();
        clock.setWall("2025-03-04T11:30:00Z");
        UnitPipeline pipeline = UnitPipeline.builder(SITE, LogPaths.parseLogPath(logfile), LineReader.cri())
                .checkpoints(store)
                .clock(clock)
                .build();
        append(logfile, LogFiles.criFull(TS, "one"));
        pipeline.poll();
        assertThat(store.load(pipeline.key()).orElseThrow().updatedAt().isoformat())
                .startsWith("2025-03-04T11:30:00");
    }
}
