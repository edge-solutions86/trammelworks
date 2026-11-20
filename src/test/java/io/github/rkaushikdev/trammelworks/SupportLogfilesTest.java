package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SupportLogfilesTest {

    static final String TS = "2025-03-04T11:30:00.123456789Z";
    static final String CID = "3f".repeat(32);

    @TempDir
    Path tmp;

    SiteTree tree;

    @BeforeEach
    void setUp() {
        tree = new SiteTree(tmp.resolve("log"));
    }

    static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void criLineHasFourSpaceSeparatedParts() {
        String line = LogFiles.criLine(TS, "stdout", "F", "auth approved");
        assertThat(line).isEqualTo(TS + " stdout F auth approved\n");
        assertThat(line.chars().filter(c -> c == ' ').count()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void criFullDefaultsToStdout() {
        assertThat(LogFiles.criFull(TS, "hello")).contains(" stdout F ");
    }

    @Test
    void criSplitProducesAPartialThenAFinal() {
        List<String> lines = LogFiles.criSplit(TS, "abcdef", 4).lines().toList();
        assertThat(lines.get(0)).endsWith(" P abcd");
        assertThat(lines.get(1)).endsWith(" F ef");
    }

    @Test
    void criSplitKeepsTheWholeMessageBetweenTheTwoChunks() {
        String text = LogFiles.criSplit(TS, "the quick brown fox", 9);
        StringBuilder joined = new StringBuilder();
        text.lines().forEach(line -> joined.append(line.split(" ", 4)[3]));
        assertThat(joined.toString()).isEqualTo("the quick brown fox");
    }

    @Test
    void dockerLineIsJsonWithTheNewlineKept() {
        Map<String, Object> payload = Json.loadsObject(LogFiles.dockerLine(TS, "auth approved"));
        assertThat(payload.get("log")).isEqualTo("auth approved\n");
        assertThat(payload.get("stream")).isEqualTo("stdout");
        assertThat(payload.get("time")).isEqualTo(TS);
    }

    @Test
    void dockerLineCanBeStderr() {
        assertThat(Json.loadsObject(LogFiles.dockerLine(TS, "boom", "stderr")).get("stream"))
                .isEqualTo("stderr");
    }

    @Test
    void journalExportEndsWithABlankLine() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("MESSAGE", "auth approved");
        fields.put("_PID", "412");
        String block = LogFiles.journalExport(fields);
        assertThat(block).endsWith("\n\n").contains("MESSAGE=auth approved");
    }

    @Test
    void siteTreeWritesACriPathTheParserRecognises() {
        Path path = tree.writeCri("pump-bridge", List.of(LogFiles.criFull(TS, "up")));
        LogPathInfo info = LogPaths.parseLogPath(path);
        assertThat(info.layout()).isSameAs(Layout.CRI);
        assertThat(info.unit()).isEqualTo("pump-bridge");
        assertThat(info.attempt()).contains(0);
    }

    @Test
    void siteTreeRecordsTheSizeItWrote() throws IOException {
        Path path = tree.writeCri("pump-bridge", List.of(LogFiles.criFull(TS, "up")));
        assertThat(tree.written().get(path.toString())).isEqualTo(Files.size(path));
    }

    @Test
    void siteTreeAppendsRatherThanReplacing() throws IOException {
        tree.writeCri("pump-bridge", List.of(LogFiles.criFull(TS, "one")));
        Path path = tree.writeCri("pump-bridge", List.of(LogFiles.criFull(TS, "two")));
        assertThat(read(path).lines()).hasSize(2);
    }

    @Test
    void siteTreeSeparatesAttempts() {
        Path first = tree.writeCri("pump-bridge", List.of(LogFiles.criFull(TS, "one")), 0);
        Path second = tree.writeCri("pump-bridge", List.of(LogFiles.criFull(TS, "two")), 1);
        assertThat(first).isNotEqualTo(second);
        assertThat(LogPaths.parseLogPath(second).attempt()).contains(1);
    }

    @Test
    void siteTreeFlatPathParses() {
        LogPathInfo info = LogPaths.parseLogPath(tree.flatPath("tank-gauge", CID));
        assertThat(info.layout()).isSameAs(Layout.FLAT);
        assertThat(info.containerId()).contains(CID);
    }

    @Test
    void siteTreeDockerPathParses() {
        assertThat(LogPaths.parseLogPath(tree.dockerPath(CID)).layout()).isSameAs(Layout.DOCKER);
    }

    @Test
    void rotateMovesTheFileAndLeavesAnEmptyOne() throws IOException {
        Path path = tree.writeCri("pump-bridge", List.of(LogFiles.criFull(TS, "one")));
        Path rotated = tree.rotate(path);
        assertThat(rotated.getFileName().toString()).endsWith(".log.1");
        assertThat(read(rotated).strip()).isNotEmpty();
        assertThat(path).exists();
        assertThat(Files.size(path)).isZero();
    }

    @Test
    void rotateTwiceReplacesThePreviousRotation() throws IOException {
        Path path = tree.writeCri("pump-bridge", List.of(LogFiles.criFull(TS, "one")));
        tree.rotate(path);
        tree.writeCri("pump-bridge", List.of(LogFiles.criFull(TS, "two")));
        assertThat(read(tree.rotate(path))).contains("two");
    }

    @Test
    void truncateCutsTheTailOff() throws IOException {
        Path path = tree.writeCri("pump-bridge", List.of(LogFiles.criFull(TS, "a long enough message")));
        tree.truncate(path, 20);
        assertThat(Files.size(path)).isEqualTo(20);
        assertThat(read(path)).doesNotEndWith("\n");
    }
}
