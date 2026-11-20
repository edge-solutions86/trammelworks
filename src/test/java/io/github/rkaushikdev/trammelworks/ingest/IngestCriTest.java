package io.github.rkaushikdev.trammelworks.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.Stream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IngestCriTest {

    static final String TS = "2025-03-04T11:30:00.123456789Z";

    static byte[] line(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aCompleteStdoutLine() {
        CriLine parsed = CriLine.parse(line(TS + " stdout F auth approved"));
        assertThat(parsed.timestamp()).isEqualTo(NanoTime.parse(TS));
        assertThat(parsed.stream()).isSameAs(Stream.STDOUT);
        assertThat(parsed.message()).isEqualTo("auth approved");
        assertThat(parsed.partial()).isFalse();
        assertThat(parsed.complete()).isTrue();
        assertThat(parsed.lossy()).isFalse();
    }

    @Test
    void aPartialLineIsFlagged() {
        CriLine parsed = CriLine.parse(line(TS + " stdout P half of a"));
        assertThat(parsed.partial()).isTrue();
        assertThat(parsed.complete()).isFalse();
    }

    @Test
    void stderrIsRead() {
        assertThat(CriLine.parse(line(TS + " stderr F boom")).stream()).isSameAs(Stream.STDERR);
    }

    @Test
    void theTrailingNewlineIsNotPartOfTheMessage() {
        assertThat(CriLine.parse(line(TS + " stdout F hello\n")).message()).isEqualTo("hello");
    }

    @Test
    void aWindowsTerminatorIsAlsoStripped() {
        assertThat(CriLine.parse(line(TS + " stdout F hello\r\n")).message()).isEqualTo("hello");
    }

    @Test
    void spacesInsideTheMessageSurvive() {
        assertThat(CriLine.parse(line(TS + " stdout F pump 3 nozzle 1 lifted")).message())
                .isEqualTo("pump 3 nozzle 1 lifted");
    }

    @Test
    void aMessageMayBeEmpty() {
        assertThat(CriLine.parse(line(TS + " stdout F ")).message()).isEmpty();
    }

    @Test
    void aMessageThatIsOnlySpacesIsKeptVerbatim() {
        assertThat(CriLine.parse(line(TS + " stdout F    ")).message()).isEqualTo("   ");
    }

    @Test
    void theNanosecondDigitsSurvive() {
        assertThat(CriLine.parse(line(TS + " stdout F x")).timestamp().nanosInSecond())
                .isEqualTo(123456789);
    }

    @Test
    void extraTagsAreKept() {
        CriLine parsed = CriLine.parse(line(TS + " stdout F:someflag x"));
        assertThat(parsed.tags()).containsExactly("someflag");
        assertThat(parsed.partial()).isFalse();
    }

    @Test
    void extraTagsOnAPartialChunk() {
        CriLine parsed = CriLine.parse(line(TS + " stdout P:a:b x"));
        assertThat(parsed.partial()).isTrue();
        assertThat(parsed.tags()).containsExactly("a", "b");
    }

    @Test
    void parseTagsReadsTheHead() {
        assertThat(CriLine.parseTags("F")).isEqualTo(new CriLine.Tags(false, List.of()));
        assertThat(CriLine.parseTags("P")).isEqualTo(new CriLine.Tags(true, List.of()));
    }

    @Test
    void parseTagsRejectsAnUnknownHead() {
        assertThatThrownBy(() -> CriLine.parseTags("Z"))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("unknown cri tag");
    }

    @Test
    void parseTagsRejectsAnEmptyField() {
        assertThatThrownBy(() -> CriLine.parseTags(":"))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("empty cri tag");
    }

    @Test
    void anEmptyLineIsRefused() {
        assertThatThrownBy(() -> CriLine.parse(line("\n")))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("empty cri line");
    }

    @ParameterizedTest
    @ValueSource(strings = {TS + " stdout", TS, "garbage"})
    void aShortLineIsRefused(String text) {
        assertThatThrownBy(() -> CriLine.parse(line(text)))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("too few fields");
    }

    @Test
    void aBadTimestampIsRefused() {
        assertThatThrownBy(() -> CriLine.parse(line("yesterday stdout F x")))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("no timestamp");
    }

    @Test
    void aBadStreamIsRefused() {
        assertThatThrownBy(() -> CriLine.parse(line(TS + " stdin F x")))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("no stream");
    }

    @Test
    void aBadTagIsRefused() {
        assertThatThrownBy(() -> CriLine.parse(line(TS + " stdout Q x")))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("bad tag");
    }

    @Test
    void theErrorCarriesThePositionItWasGiven() {
        ParseError caught = catchThrowableOfType(
                ParseError.class, () -> CriLine.parse(line("junk"), "/var/log/pods/a/0.log", 8192));
        assertThat(caught.source()).contains("/var/log/pods/a/0.log");
        assertThat(caught.offset()).contains(8192L);
    }

    @Test
    void invalidUtf8InTheMessageIsReplacedAndReported() {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.writeBytes(line(TS + " stdout F frame "));
        raw.write(0xff);
        raw.write(0xfe);
        raw.writeBytes(line(" end"));
        CriLine parsed = CriLine.parse(raw.toByteArray());
        assertThat(parsed.lossy()).isTrue();
        assertThat(parsed.message()).startsWith("frame ").endsWith(" end").contains("\uFFFD");
    }

    @Test
    void validMultibyteUtf8IsNotLossy() {
        CriLine parsed = CriLine.parse(line(TS + " stdout F prix affiché"));
        assertThat(parsed.lossy()).isFalse();
        assertThat(parsed.message()).isEqualTo("prix affiché");
    }

    @Test
    void aTabInTheMessageIsNotASeparator() {
        assertThat(CriLine.parse(line(TS + " stdout F a\tb")).message()).isEqualTo("a\tb");
    }

    @Test
    void renderRoundTripsACompleteLine() {
        CriLine original = CriLine.parse(line(TS + " stdout F auth approved"));
        assertThat(CriLine.parse(original.render())).isEqualTo(original);
    }

    @Test
    void renderRoundTripsAPartialLineWithTags() {
        CriLine original = CriLine.parse(line(TS + " stderr P:x chunk"));
        assertThat(CriLine.parse(original.render())).isEqualTo(original);
    }

    @Test
    void renderEndsWithANewline() {
        byte[] rendered = new CriLine(NanoTime.parse(TS), Stream.STDOUT, "x").render();
        assertThat(rendered[rendered.length - 1]).isEqualTo((byte) '\n');
        int newlines = 0;
        for (byte b : rendered) {
            newlines += b == '\n' ? 1 : 0;
        }
        assertThat(newlines).isEqualTo(1);
    }
}
