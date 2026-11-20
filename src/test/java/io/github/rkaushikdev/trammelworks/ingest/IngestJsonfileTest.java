package io.github.rkaushikdev.trammelworks.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.Stream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IngestJsonfileTest {

    static final String TS = "2025-03-04T11:30:00.123456789Z";

    static byte[] raw(Object... over) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("log", "auth approved\n");
        payload.put("stream", "stdout");
        payload.put("time", TS);
        payload.putAll(Maps.of(over));
        return (Json.dumps(payload) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aCompleteRecord() {
        JsonFileLine parsed = JsonFileLine.parse(raw());
        assertThat(parsed.message()).isEqualTo("auth approved");
        assertThat(parsed.stream()).isSameAs(Stream.STDOUT);
        assertThat(parsed.timestamp()).isEqualTo(NanoTime.parse(TS));
        assertThat(parsed.partial()).isFalse();
        assertThat(parsed.complete()).isTrue();
    }

    @Test
    void aRecordWithNoTrailingNewlineIsPartial() {
        JsonFileLine parsed = JsonFileLine.parse(raw("log", "half of a line"));
        assertThat(parsed.partial()).isTrue();
        assertThat(parsed.message()).isEqualTo("half of a line");
    }

    @Test
    void onlyTheFinalNewlineIsRemoved() {
        assertThat(JsonFileLine.parse(raw("log", "two\nlines\n")).message()).isEqualTo("two\nlines");
    }

    @Test
    void aLogFieldOfJustANewlineIsAnEmptyCompleteLine() {
        JsonFileLine parsed = JsonFileLine.parse(raw("log", "\n"));
        assertThat(parsed.message()).isEmpty();
        assertThat(parsed.partial()).isFalse();
    }

    @Test
    void anEmptyLogFieldIsPartialBecauseItHasNoNewline() {
        JsonFileLine parsed = JsonFileLine.parse(raw("log", ""));
        assertThat(parsed.message()).isEmpty();
        assertThat(parsed.partial()).isTrue();
    }

    @Test
    void stderrIsRead() {
        assertThat(JsonFileLine.parse(raw("stream", "stderr")).stream()).isSameAs(Stream.STDERR);
    }

    @Test
    void aMissingStreamDefaultsToStdout() {
        Map<String, Object> payload = Maps.of("log", "x\n", "time", TS);
        assertThat(JsonFileLine.parse(bytes(Json.dumps(payload))).stream()).isSameAs(Stream.STDOUT);
    }

    @Test
    void attrsAreKeptAsStrings() {
        JsonFileLine parsed = JsonFileLine.parse(raw("attrs", Maps.of("tag", "pump", "lane", 3)));
        assertThat(parsed.attrs()).isEqualTo(Map.of("tag", "pump", "lane", "3"));
    }

    @Test
    void absentAttrsGiveAnEmptyMapping() {
        assertThat(JsonFileLine.parse(raw()).attrs()).isEmpty();
    }

    @Test
    void nonObjectAttrsAreRefused() {
        assertThatThrownBy(() -> JsonFileLine.parse(raw("attrs", List.of("a"))))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("attrs is not an object");
    }

    @Test
    void anEmptyLineIsRefused() {
        assertThatThrownBy(() -> JsonFileLine.parse(bytes("   \n")))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("empty json-file line");
    }

    @Test
    void aNonJsonLineIsRefused() {
        assertThatThrownBy(() -> JsonFileLine.parse(bytes("{not json\n")))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("not json");
    }

    @Test
    void aJsonArrayIsRefused() {
        assertThatThrownBy(() -> JsonFileLine.parse(bytes("[1, 2]\n")))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("not an object");
    }

    @Test
    void aRecordWithoutALogFieldIsRefused() {
        assertThatThrownBy(() -> JsonFileLine.parse(bytes(Json.dumps(Map.of("time", TS)))))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("no log field");
    }

    @Test
    void aNonStringLogFieldIsRefused() {
        assertThatThrownBy(() -> JsonFileLine.parse(raw("log", 42)))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("log field is not a string");
    }

    @Test
    void aRecordWithoutATimeIsRefused() {
        assertThatThrownBy(() -> JsonFileLine.parse(bytes(Json.dumps(Map.of("log", "x\n")))))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("no time field");
    }

    @Test
    void aBadTimeIsRefused() {
        assertThatThrownBy(() -> JsonFileLine.parse(raw("time", "yesterday")))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("bad time");
    }

    @Test
    void aBadStreamIsRefused() {
        assertThatThrownBy(() -> JsonFileLine.parse(raw("stream", "stdin")))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("bad stream");
    }

    @Test
    void theErrorCarriesThePosition() {
        ParseError caught = catchThrowableOfType(
                ParseError.class, () -> JsonFileLine.parse(bytes("junk"), "/var/lib/docker/x-json.log", 64));
        assertThat(caught.source()).contains("/var/lib/docker/x-json.log");
        assertThat(caught.offset()).contains(64L);
    }

    @Test
    void invalidUtf8IsReplacedAndReported() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(bytes("{\"log\":\"frame "));
        body.write(0xff);
        body.write(0xfe);
        body.writeBytes(bytes("\\n\",\"stream\":\"stdout\",\"time\":\"" + TS + "\"}"));
        JsonFileLine parsed = JsonFileLine.parse(body.toByteArray());
        assertThat(parsed.lossy()).isTrue();
        assertThat(parsed.message()).contains("\uFFFD");
    }

    @Test
    void validMultibyteTextIsNotLossy() {
        JsonFileLine parsed = JsonFileLine.parse(raw("log", "prix affiché\n"));
        assertThat(parsed.lossy()).isFalse();
        assertThat(parsed.message()).isEqualTo("prix affiché");
    }

    @Test
    void renderRoundTripsACompleteRecord() {
        JsonFileLine original = JsonFileLine.parse(raw());
        assertThat(JsonFileLine.parse(original.render())).isEqualTo(original);
    }

    @Test
    void renderRoundTripsAPartialRecord() {
        JsonFileLine original = JsonFileLine.parse(raw("log", "no newline here"));
        assertThat(JsonFileLine.parse(original.render())).isEqualTo(original);
    }

    @Test
    void renderRoundTripsAttrs() {
        JsonFileLine original = JsonFileLine.parse(raw("attrs", Map.of("tag", "pump")));
        assertThat(JsonFileLine.parse(original.render()).attrs()).isEqualTo(Map.of("tag", "pump"));
    }

    @Test
    void renderPutsTheNewlineBackInsideTheLogField() {
        JsonFileLine original = JsonFileLine.parse(raw());
        Map<String, Object> payload = Json.loadsObject(new String(original.render(), StandardCharsets.UTF_8));
        assertThat(payload.get("log")).isEqualTo("auth approved\n");
    }
}
