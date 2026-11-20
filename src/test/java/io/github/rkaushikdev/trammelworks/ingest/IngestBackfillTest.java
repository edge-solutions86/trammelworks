package io.github.rkaushikdev.trammelworks.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.IngestError;
import io.github.rkaushikdev.trammelworks.LogFiles;
import io.github.rkaushikdev.trammelworks.LogPathInfo;
import io.github.rkaushikdev.trammelworks.LogPaths;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.SiteTree;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestBackfillTest {

    static final String TS = "2025-03-04T12:00:00.123456789Z";
    static final String SITE = "US-TX-0417";

    @TempDir
    Path tmp;

    Path live;

    @BeforeEach
    void setUp() {
        live = new SiteTree(tmp.resolve("log")).writeCri("pump-bridge", List.of(LogFiles.criFull(TS, "the live file")));
    }

    Path rotated(String suffix, String text) {
        return rotated(suffix, text, false);
    }

    Path rotated(String suffix, String text, boolean compress) {
        Path target = live.resolveSibling(live.getFileName() + suffix);
        try {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            if (compress) {
                ByteArrayOutputStream packed = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(packed)) {
                    gzip.write(body);
                }
                body = packed.toByteArray();
            }
            return Files.write(target, body);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    Backfiller backfiller() {
        return new Backfiller(SITE, LogPaths.parseLogPath(live));
    }

    static List<String> messages(List<LogRecord> records) {
        return records.stream().map(LogRecord::message).toList();
    }

    @Test
    void aLiveFileWithNoRotationsHasNoSources() {
        assertThat(Backfiller.rotatedSiblings(live)).isEmpty();
    }

    @Test
    void aRotatedFileIsFound() {
        rotated(".1", LogFiles.criFull(TS, "older"));
        assertThat(Backfiller.rotatedSiblings(live))
                .extracting(LogPathInfo::rotation)
                .containsExactly(1);
    }

    @Test
    void theLiveFileIsNeverASource() {
        rotated(".1", LogFiles.criFull(TS, "older"));
        assertThat(Backfiller.rotatedSiblings(live))
                .noneMatch(item -> Path.of(item.path()).equals(live));
    }

    @Test
    void rotationsComeBackOldestFirst() {
        rotated(".1", LogFiles.criFull(TS, "newer"));
        rotated(".2", LogFiles.criFull(TS, "older"));
        assertThat(Backfiller.rotatedSiblings(live))
                .extracting(LogPathInfo::rotation)
                .containsExactly(2, 1);
    }

    @Test
    void aCompressedRotationIsFound() {
        rotated(".2.gz", LogFiles.criFull(TS, "packed"), true);
        List<LogPathInfo> found = Backfiller.rotatedSiblings(live);
        assertThat(found).isNotEmpty();
        assertThat(found.get(0).compressed()).isTrue();
    }

    @Test
    void anUnrelatedFileBesideItIsIgnored() throws IOException {
        Files.writeString(live.resolveSibling("notes.txt"), "hello");
        assertThat(Backfiller.rotatedSiblings(live)).isEmpty();
    }

    @Test
    void readWholeReadsAPlainFile() {
        assertThat(Backfiller.readWhole(rotated(".1", "hello"), false, 1024)).isEqualTo("hello".getBytes());
    }

    @Test
    void readWholeDecompresses() {
        assertThat(Backfiller.readWhole(rotated(".1.gz", "hello", true), true, 1024))
                .isEqualTo("hello".getBytes());
    }

    @Test
    void readWholeRefusesAFileOverTheCap() {
        Path target = rotated(".1", "x".repeat(100));
        assertThatThrownBy(() -> Backfiller.readWhole(target, false, 10))
                .isInstanceOf(IngestError.class)
                .hasMessageContaining("larger than the cap");
    }

    @Test
    void readWholeRefusesAMissingFile() {
        assertThatThrownBy(() -> Backfiller.readWhole(live.resolveSibling("absent.log"), false, 1024))
                .isInstanceOf(IngestError.class)
                .hasMessageContaining("unreadable");
    }

    @Test
    void readWholeRefusesSomethingThatIsNotGzip() {
        Path target = rotated(".1.gz", "not actually gzip");
        assertThatThrownBy(() -> Backfiller.readWhole(target, true, 1024))
                .isInstanceOf(IngestError.class)
                .hasMessageContaining("not gzip");
    }

    @Test
    void recordsComeOutOfARotatedFile() {
        rotated(".1", LogFiles.criFull(TS, "one") + LogFiles.criFull(TS, "two"));
        assertThat(messages(backfiller().records())).containsExactly("one", "two");
    }

    @Test
    void recordsComeOutOfACompressedFile() {
        rotated(".1.gz", LogFiles.criFull(TS, "packed one"), true);
        assertThat(messages(backfiller().records())).containsExactly("packed one");
    }

    @Test
    void theRecordsCarryTheSiteAndUnit() {
        rotated(".1", LogFiles.criFull(TS, "one"));
        LogRecord found = backfiller().records().get(0);
        assertThat(found.site()).isEqualTo(SITE);
        assertThat(found.unit()).isEqualTo("pump-bridge");
    }

    @Test
    void olderFilesComeFirst() {
        rotated(".1", LogFiles.criFull(TS, "newer"));
        rotated(".2", LogFiles.criFull(TS, "older"));
        assertThat(messages(backfiller().records())).containsExactly("older", "newer");
    }

    @Test
    void splitChunksInsideOneFileAreJoined() {
        rotated(".1", LogFiles.criLine(TS, "stdout", "P", "first ") + LogFiles.criLine(TS, "stdout", "F", "second"));
        assertThat(messages(backfiller().records())).containsExactly("first second");
    }

    @Test
    void aLineLeftOpenAtTheEndIsFlushed() {
        rotated(".1", LogFiles.criLine(TS, "stdout", "P", "never closed"));
        List<LogRecord> found = backfiller().records();
        assertThat(messages(found)).containsExactly("never closed");
        assertThat(found.get(0).labels().get("truncated")).isEqualTo("true");
    }

    @Test
    void aChunkIsNotJoinedAcrossARotation() {
        rotated(".2", LogFiles.criLine(TS, "stdout", "P", "half from the older file"));
        rotated(".1", LogFiles.criFull(TS, "a whole line from the newer one"));
        List<LogRecord> found = backfiller().records();
        assertThat(found).hasSize(2);
        assertThat(found.get(0).message()).isEqualTo("half from the older file");
    }

    @Test
    void theReportCountsFilesAndRecords() {
        rotated(".1", LogFiles.criFull(TS, "one"));
        rotated(".2", LogFiles.criFull(TS, "two") + LogFiles.criFull(TS, "three"));
        Backfiller built = backfiller();
        built.records();
        assertThat(built.report().files()).isEqualTo(2);
        assertThat(built.report().records()).isEqualTo(3);
        assertThat(built.report().bytesRead()).isPositive();
    }

    @Test
    void theReportReadsAsOneLine() {
        rotated(".1", LogFiles.criFull(TS, "one"));
        Backfiller built = backfiller();
        built.records();
        assertThat(built.report().toString()).isEqualTo("backfilled 1 records from 1 files");
    }

    @Test
    void anUnreadableFileIsNamedAndSkipped() {
        rotated(".1.gz", "not gzip at all");
        rotated(".2", LogFiles.criFull(TS, "still readable"));
        Backfiller built = backfiller();
        assertThat(messages(built.records())).containsExactly("still readable");
        assertThat(built.report().failed()).isNotEmpty();
    }

    @Test
    void aBadLineInsideAFileIsSkipped() {
        rotated(".1", LogFiles.criFull(TS, "one") + "garbage\n" + LogFiles.criFull(TS, "three"));
        Backfiller built = backfiller();
        assertThat(messages(built.records())).containsExactly("one", "three");
        assertThat(built.report().skipped()).isEqualTo(1);
    }

    @Test
    void anEmptyRotatedFileYieldsNothingAndIsNotAFailure() {
        // logrotate leaves an empty file behind routinely. There is nothing in it and nothing wrong
        // with it.
        rotated(".1", "");
        Backfiller built = backfiller();
        assertThat(built.records()).isEmpty();
        assertThat(built.report().failed()).isEmpty();
        assertThat(built.report().files()).isEqualTo(1);
    }
}
