package io.github.rkaushikdev.trammelworks.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.Fragment;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class IngestReadersTest {

    static final String PATH = "/var/log/pods/forecourt_x_uid/payment-kernel/0.log";
    static final String TS = "2025-03-04T11:30:00.123456789Z";

    static byte[] cri(String message) {
        return cri(message, "F", "stdout");
    }

    static byte[] cri(String message, String tag, String stream) {
        return bytes(TS + " " + stream + " " + tag + " " + message + "\n");
    }

    static byte[] jsonl(String message) {
        return bytes(Json.dumps(Maps.of("log", message, "stream", "stdout", "time", TS)) + "\n");
    }

    static byte[] journal(String message) {
        return journal(message, 1741087800123456L);
    }

    static byte[] journal(String message, long micros) {
        return bytes("__REALTIME_TIMESTAMP=" + micros + "\nMESSAGE=" + message + "\n\n");
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static java.util.stream.Stream<FragmentReader> readers() {
        return java.util.stream.Stream.of(LineReader.cri(), LineReader.jsonFile(), new JournalReader());
    }

    @ParameterizedTest
    @MethodSource("readers")
    void everyReaderSatisfiesTheProtocol(FragmentReader reader) {
        assertThat(reader).isInstanceOf(FragmentReader.class);
        assertThat(reader.read(new byte[0], PATH).consumed()).isZero();
    }

    @Test
    void criReadsTwoLines() {
        ReadOutcome result = LineReader.cri().read(concat(cri("one"), cri("two")), PATH);
        assertThat(result.fragments()).extracting(Fragment::message).containsExactly("one", "two");
        assertThat(result.consumed()).isEqualTo(cri("one").length + cri("two").length);
    }

    @Test
    void criLeavesAHalfWrittenLineAlone() {
        ReadOutcome result = LineReader.cri().read(concat(cri("one"), bytes(TS + " stdout F unfinis")), PATH);
        assertThat(result.fragments()).extracting(Fragment::message).containsExactly("one");
        assertThat(result.consumed()).isEqualTo(cri("one").length);
    }

    @Test
    void criConsumedIsZeroWhenNothingIsComplete() {
        assertThat(LineReader.cri().read(bytes("no terminator yet"), PATH).consumed())
                .isZero();
    }

    @Test
    void criRecordsTheOffsetOfEachLine() {
        ReadOutcome result = LineReader.cri().read(concat(cri("one"), cri("two")), PATH);
        assertThat(result.fragments().get(0).source().offset()).isZero();
        assertThat(result.fragments().get(1).source().offset()).isEqualTo(cri("one").length);
    }

    @Test
    void criAppliesTheBaseOffset() {
        assertThat(LineReader.cri()
                        .read(cri("one"), PATH, 4096)
                        .fragments()
                        .get(0)
                        .source()
                        .offset())
                .isEqualTo(4096);
    }

    @Test
    void criSourceLengthIncludesTheTerminator() {
        byte[] line = cri("one");
        assertThat(LineReader.cri().read(line, PATH).fragments().get(0).source().length())
                .isEqualTo(line.length);
    }

    @Test
    void criKeepsThePartialFlag() {
        assertThat(LineReader.cri()
                        .read(cri("half", "P", "stdout"), PATH)
                        .fragments()
                        .get(0)
                        .partial())
                .isTrue();
    }

    @Test
    void criKeepsTheStream() {
        assertThat(LineReader.cri().read(cri("boom", "F", "stderr"), PATH).fragments().get(0).stream())
                .isSameAs(Stream.STDERR);
    }

    @Test
    void criKeepsTheNanosecondTimestamp() {
        assertThat(LineReader.cri().read(cri("one"), PATH).fragments().get(0).observedAt())
                .isEqualTo(NanoTime.parse(TS));
    }

    @Test
    void criSkipsABadLineAndCarriesOn() {
        ReadOutcome result = LineReader.cri().read(concat(cri("one"), bytes("garbage\n"), cri("three")), PATH);
        assertThat(result.fragments()).extracting(Fragment::message).containsExactly("one", "three");
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
    }

    @Test
    void aSkippedLineIsStillConsumed() {
        byte[] data = concat(cri("one"), bytes("garbage\n"));
        assertThat(LineReader.cri().read(data, PATH).consumed()).isEqualTo(data.length);
    }

    @Test
    void theErrorNamesTheAbsoluteOffset() {
        ReadOutcome result = LineReader.cri().read(bytes("garbage\n"), PATH, 1000);
        assertThat(result.errors().get(0).offset()).contains(1000L);
        assertThat(result.errors().get(0).source()).contains(PATH);
    }

    @Test
    void criSkipsABlankLineWithoutAnError() {
        ReadOutcome result = LineReader.cri().read(concat(bytes("\n"), cri("one")), PATH);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void jsonfileReadsRecords() {
        ReadOutcome result = LineReader.jsonFile().read(concat(jsonl("one\n"), jsonl("two\n")), PATH);
        assertThat(result.fragments()).extracting(Fragment::message).containsExactly("one", "two");
    }

    @Test
    void jsonfileMarksARecordWithoutANewlinePartial() {
        assertThat(LineReader.jsonFile()
                        .read(jsonl("half"), PATH)
                        .fragments()
                        .get(0)
                        .partial())
                .isTrue();
    }

    @Test
    void jsonfileLeavesAnIncompleteRecordAlone() {
        ReadOutcome result = LineReader.jsonFile().read(concat(jsonl("one\n"), bytes("{\"log\": \"two")), PATH);
        assertThat(result.fragments()).hasSize(1);
        assertThat(result.consumed()).isEqualTo(jsonl("one\n").length);
    }

    @Test
    void jsonfileSkipsABrokenRecord() {
        ReadOutcome result = LineReader.jsonFile().read(concat(bytes("{oops}\n"), jsonl("ok\n")), PATH);
        assertThat(result.fragments()).extracting(Fragment::message).containsExactly("ok");
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void journalReadsABlock() {
        assertThat(new JournalReader().read(journal("auth approved"), PATH).fragments())
                .extracting(Fragment::message)
                .containsExactly("auth approved");
    }

    @Test
    void journalReadsTwoBlocks() {
        assertThat(new JournalReader()
                        .read(concat(journal("one"), journal("two")), PATH)
                        .fragments())
                .hasSize(2);
    }

    @Test
    void journalLeavesAnUnterminatedBlockAlone() {
        byte[] data = concat(journal("one"), bytes("__REALTIME_TIMESTAMP=1741087800123456\nMESSAGE=two\n"));
        ReadOutcome result = new JournalReader().read(data, PATH);
        assertThat(result.fragments()).hasSize(1);
        assertThat(result.consumed()).isEqualTo(journal("one").length);
    }

    @Test
    void journalEntriesAreNeverPartial() {
        assertThat(new JournalReader()
                        .read(journal("x"), PATH)
                        .fragments()
                        .get(0)
                        .partial())
                .isFalse();
    }

    @Test
    void journalReadsTheRealtimeStamp() {
        assertThat(new JournalReader()
                        .read(journal("x", 1741087800000000L), PATH)
                        .fragments()
                        .get(0)
                        .observedAt())
                .isEqualTo(NanoTime.parse("2025-03-04T11:30:00Z"));
    }

    @Test
    void journalSkipsABlockWithNoTimestamp() {
        ReadOutcome result = new JournalReader().read(concat(bytes("MESSAGE=orphan\n\n"), journal("good")), PATH);
        assertThat(result.fragments()).extracting(Fragment::message).containsExactly("good");
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void journalMapsPriorityToTheStream() {
        byte[] block = bytes("__REALTIME_TIMESTAMP=1741087800123456\nPRIORITY=3\nMESSAGE=boom\n\n");
        assertThat(new JournalReader().read(block, PATH).fragments().get(0).stream())
                .isSameAs(Stream.STDERR);
    }

    @Test
    void readerForPicksTheCriReader() {
        assertThat(FragmentReader.forFormat(Format.CRI).read(cri("one"), PATH).fragments())
                .extracting(Fragment::message)
                .containsExactly("one");
    }

    @Test
    void readerForPicksTheJsonfileReader() {
        assertThat(FragmentReader.forFormat(Format.JSONFILE)
                        .read(jsonl("one\n"), PATH)
                        .fragments())
                .extracting(Fragment::message)
                .containsExactly("one");
    }

    @Test
    void readerForPicksTheJournalReader() {
        assertThat(FragmentReader.forFormat(Format.JOURNAL)
                        .read(journal("one"), PATH)
                        .fragments())
                .extracting(Fragment::message)
                .containsExactly("one");
    }

    @Test
    void theTwoLineReadersShareOneWalk() {
        assertThat(LineReader.cri()).isInstanceOf(LineReader.class);
        assertThat(LineReader.jsonFile()).isInstanceOf(LineReader.class);
    }

    @Test
    void theSharedWalkReportsTheSameConsumedCount() {
        ReadOutcome left = LineReader.cri().read(concat(cri("one"), bytes("partial")), PATH);
        ReadOutcome right = LineReader.jsonFile().read(concat(jsonl("one\n"), bytes("partial")), PATH);
        assertThat(left.consumed()).isEqualTo(cri("one").length);
        assertThat(right.consumed()).isEqualTo(jsonl("one\n").length);
    }

    static byte[] journalPositioned(String message) {
        return bytes(String.join(
                "\n",
                "__REALTIME_TIMESTAMP=1741087800123456",
                "__MONOTONIC_TIMESTAMP=98765432",
                "_BOOT_ID=4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d",
                "MESSAGE=" + message,
                "",
                ""));
    }

    @Test
    void journalPassesTheMonotonicPositionThrough() {
        assertThat(new JournalReader()
                        .read(journalPositioned("auth"), PATH)
                        .fragments()
                        .get(0)
                        .labels()
                        .get("monotonic_ns"))
                .isEqualTo(Long.toString(98765432L * 1000));
    }

    @Test
    void journalPassesTheBootIdThrough() {
        assertThat(new JournalReader()
                        .read(journalPositioned("auth"), PATH)
                        .fragments()
                        .get(0)
                        .labels()
                        .get("boot_id"))
                .isEqualTo("4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d");
    }

    @Test
    void anEntryWithNoPositionCarriesNoLabels() {
        assertThat(new JournalReader()
                        .read(journal("plain"), PATH)
                        .fragments()
                        .get(0)
                        .labels())
                .isEmpty();
    }

    @Test
    void theCriReaderLeavesLabelsEmpty() {
        assertThat(LineReader.cri().read(cri("one"), PATH).fragments().get(0).labels())
                .isEmpty();
    }
}
