package io.github.rkaushikdev.trammelworks.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.Stream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IngestJournalTest {

    static final String BOOT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d";
    static final byte[] TEXT_ENTRY = bytes("__REALTIME_TIMESTAMP=1741087800123456\n"
            + "__MONOTONIC_TIMESTAMP=98765432100\n"
            + "_BOOT_ID=" + BOOT + "\n"
            + "PRIORITY=6\n"
            + "CONTAINER_NAME=payment-kernel\n"
            + "MESSAGE=auth approved\n"
            + "\n");

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

    static byte[] length(long size) {
        return ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(size)
                .array();
    }

    static byte[] binaryField(String name, String value) {
        byte[] encoded = bytes(value);
        return concat(bytes(name + "\n"), length(encoded.length), encoded, bytes("\n"));
    }

    @Test
    void oneTextEntry() {
        List<JournalEntry> entries = Journal.parseExport(TEXT_ENTRY);
        assertThat(entries).hasSize(1);
        JournalEntry entry = entries.get(0);
        assertThat(entry.message()).isEqualTo("auth approved");
        assertThat(entry.unit()).contains("payment-kernel");
        assertThat(entry.bootId()).contains(BOOT);
    }

    @Test
    void realtimeIsReadAsMicroseconds() {
        assertThat(Journal.parseExport(TEXT_ENTRY).get(0).realtime())
                .isEqualTo(NanoTime.parse("2025-03-04T11:30:00.123456Z"));
    }

    @Test
    void monotonicIsRead() {
        assertThat(Journal.parseExport(TEXT_ENTRY).get(0).monotonicUs()).hasValue(98765432100L);
    }

    @Test
    void anAbsentMonotonicIsNone() {
        assertThat(new JournalEntry(Map.of("MESSAGE", "x")).monotonicUs()).isEmpty();
    }

    @Test
    void aMissingRealtimeIsAnError() {
        assertThatThrownBy(() -> new JournalEntry(Map.of("MESSAGE", "x")).realtime())
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("no realtime timestamp");
    }

    @Test
    void aNonNumericRealtimeIsAnError() {
        assertThatThrownBy(() -> new JournalEntry(Map.of("__REALTIME_TIMESTAMP", "soon")).realtime())
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("realtime is not an integer");
    }

    @Test
    void aNonNumericMonotonicIsAnError() {
        assertThatThrownBy(() -> new JournalEntry(Map.of("__MONOTONIC_TIMESTAMP", "soon")).monotonicUs())
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("monotonic is not an integer");
    }

    @Test
    void prioritySixIsStdout() {
        assertThat(Journal.parseExport(TEXT_ENTRY).get(0).stream()).isSameAs(Stream.STDOUT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "2", "3"})
    void priorityAtOrBelowErrorIsStderr(String priority) {
        assertThat(new JournalEntry(Map.of("PRIORITY", priority)).stream()).isSameAs(Stream.STDERR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"4", "5", "6", "7"})
    void priorityAboveErrorIsStdout(String priority) {
        assertThat(new JournalEntry(Map.of("PRIORITY", priority)).stream()).isSameAs(Stream.STDOUT);
    }

    @Test
    void aMissingPriorityDefaultsToStdout() {
        assertThat(new JournalEntry(Map.of("MESSAGE", "x")).stream()).isSameAs(Stream.STDOUT);
    }

    @Test
    void aNonNumericPriorityIsAnError() {
        assertThatThrownBy(() -> new JournalEntry(Map.of("PRIORITY", "high")).stream())
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("priority is not an integer");
    }

    @Test
    void twoEntriesAreSplitOnTheBlankLine() {
        assertThat(Journal.parseExport(concat(TEXT_ENTRY, TEXT_ENTRY))).hasSize(2);
    }

    @Test
    void aTrailingEntryWithoutABlankLineIsStillReturned() {
        List<JournalEntry> entries = Journal.parseExport(bytes("MESSAGE=last one\n"));
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).message()).isEqualTo("last one");
    }

    @Test
    void repeatedBlankLinesDoNotMakeEmptyEntries() {
        assertThat(Journal.parseExport(concat(bytes("\n\n"), TEXT_ENTRY, bytes("\n\n"))))
                .hasSize(1);
    }

    @Test
    void anEmptyStreamGivesNoEntries() {
        assertThat(Journal.parseExport(new byte[0])).isEmpty();
    }

    @Test
    void aBinaryFieldCarriesItsNewlines() {
        String trace = "Traceback:\n  line one\n  line two";
        byte[] data =
                concat(bytes("__REALTIME_TIMESTAMP=1741087800000000\n"), binaryField("MESSAGE", trace), bytes("\n"));
        JournalEntry entry = Journal.parseExport(data).get(0);
        assertThat(entry.message()).isEqualTo(trace);
        assertThat(entry.binary()).contains("MESSAGE");
    }

    @Test
    void aBinaryFieldDoesNotEndTheEntry() {
        byte[] data = concat(
                bytes("__REALTIME_TIMESTAMP=1741087800000000\n"),
                binaryField("MESSAGE", "one\ntwo"),
                bytes("CONTAINER_NAME=payment-kernel\n\n"));
        assertThat(Journal.parseExport(data).get(0).unit()).contains("payment-kernel");
    }

    @Test
    void aTruncatedBinaryHeaderIsRefused() {
        assertThatThrownBy(() -> Journal.parseExport(concat(bytes("MESSAGE\n"), new byte[] {1, 2})))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("binary field is truncated");
    }

    @Test
    void aBinaryValueThatOverrunsIsRefused() {
        byte[] data = concat(bytes("MESSAGE\n"), length(999), bytes("short"));
        assertThatThrownBy(() -> Journal.parseExport(data))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("runs past the end");
    }

    @Test
    void aValueContainingAnEqualsSignIsKeptWhole() {
        assertThat(Journal.parseExport(bytes("MESSAGE=a=b=c\n\n")).get(0).message())
                .isEqualTo("a=b=c");
    }

    @Test
    void invalidUtf8InAValueIsReplaced() {
        byte[] data = concat(bytes("MESSAGE=frame "), new byte[] {(byte) 0xff, (byte) 0xfe}, bytes("\n\n"));
        assertThat(Journal.parseExport(data).get(0).message()).contains("\uFFFD");
    }

    @Test
    void getReturnsTheDefaultForAnAbsentField() {
        assertThat(new JournalEntry().get("MESSAGE", "none")).isEqualTo("none");
    }

    @Test
    void renderRoundTripsATextEntry() {
        JournalEntry original = Journal.parseExport(TEXT_ENTRY).get(0);
        assertThat(Journal.parseExport(Journal.render(original)).get(0)).isEqualTo(original);
    }

    @Test
    void renderUsesTheBinaryFormForAMultilineValue() {
        byte[] rendered = Journal.render(new JournalEntry(Map.of("MESSAGE", "one\ntwo")));
        assertThat(new String(rendered, StandardCharsets.UTF_8)).contains("MESSAGE\n");
        assertThat(Journal.parseExport(rendered).get(0).message()).isEqualTo("one\ntwo");
    }

    @Test
    void renderKeepsAFieldBinaryOnceItWasReadThatWay() {
        byte[] rendered = Journal.render(new JournalEntry(Map.of("MESSAGE", "plain"), Set.of("MESSAGE")));
        assertThat(new String(rendered, StandardCharsets.UTF_8)).doesNotContain("MESSAGE=plain");
        assertThat(Journal.parseExport(rendered).get(0).message()).isEqualTo("plain");
    }
}
