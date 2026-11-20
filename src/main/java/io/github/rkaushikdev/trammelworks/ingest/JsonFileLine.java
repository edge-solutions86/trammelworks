package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Bytes;
import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.Stream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One docker json-file record: {@code {"log":"auth approved\n","stream":"stdout","time":"..."}}.
 *
 * <p>This driver has no partial flag. A chunk that continues is signalled by the absence of a
 * trailing newline inside {@code log}, so the same fact is a present marker in CRI and a missing
 * character here. The last record of a live file has no newline because the writer has not
 * finished yet; the tailer resolves that, and the parser only reports what the bytes say.
 *
 * @param timestamp the {@code time} field
 * @param stream the {@code stream} field, stdout when absent
 * @param message the {@code log} field without its final newline
 * @param partial whether {@code log} lacked a final newline
 * @param lossy whether invalid UTF-8 had to be replaced
 * @param attrs the {@code attrs} object, values as text
 */
public record JsonFileLine(
        NanoTime timestamp, Stream stream, String message, boolean partial, boolean lossy, Map<String, String> attrs)
        implements ParsedLine {

    static final String LOG_KEY = "log";
    static final String STREAM_KEY = "stream";
    static final String TIME_KEY = "time";
    static final String ATTRS_KEY = "attrs";

    /** Copies the attributes. */
    public JsonFileLine {
        attrs = Maps.frozen(attrs);
    }

    /** Whether this record ends an application line. */
    public boolean complete() {
        return !partial;
    }

    /** Parse one record with no position to report. */
    public static JsonFileLine parse(byte[] raw) {
        return parse(raw, null, 0);
    }

    /** Parse one json-file record. */
    public static JsonFileLine parse(byte[] raw, String source, long offset) {
        byte[] body = Bytes.strip(raw);
        if (body.length == 0) {
            throw new ParseError("empty json-file line", null, source, offset);
        }
        Bytes.Decoded text = Bytes.decode(body);
        Object payload;
        try {
            payload = Json.loads(text.text());
        } catch (Json.JsonException exc) {
            throw new ParseError("json-file line is not json", null, source, offset);
        }
        if (!(payload instanceof Map<?, ?> fields)) {
            throw new ParseError("json-file line is not an object", null, source, offset);
        }
        if (!fields.containsKey(LOG_KEY)) {
            throw new ParseError("json-file line has no log field", null, source, offset);
        }
        if (!(fields.get(LOG_KEY) instanceof String message)) {
            throw new ParseError("json-file log field is not a string", null, source, offset);
        }
        if (!fields.containsKey(TIME_KEY)) {
            throw new ParseError("json-file line has no time field", null, source, offset);
        }
        NanoTime timestamp;
        try {
            timestamp = NanoTime.parse(Coerce.text(fields.get(TIME_KEY)));
        } catch (ParseError exc) {
            throw new ParseError("json-file line has a bad time", null, source, offset);
        }
        Stream stream;
        try {
            Object rawStream = fields.containsKey(STREAM_KEY) ? fields.get(STREAM_KEY) : "stdout";
            stream = Stream.parse(Coerce.text(rawStream));
        } catch (IllegalArgumentException exc) {
            throw new ParseError("json-file line has a bad stream", null, source, offset);
        }
        boolean partial = !message.endsWith("\n");
        String trimmed = partial ? message : message.substring(0, message.length() - 1);
        return new JsonFileLine(timestamp, stream, trimmed, partial, text.lossy(), attrs(fields.get(ATTRS_KEY)));
    }

    private static Map<String, String> attrs(Object payload) {
        if (payload == null) {
            return Map.of();
        }
        if (!(payload instanceof Map<?, ?> map)) {
            throw new ParseError("json-file attrs is not an object");
        }
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(Coerce.text(key), Coerce.text(value)));
        return out;
    }

    /** Render back to the wire form, with the newline back inside {@code log}. */
    public byte[] render() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(LOG_KEY, partial ? message : message + "\n");
        payload.put(STREAM_KEY, stream.value());
        payload.put(TIME_KEY, timestamp.isoformat());
        if (!attrs.isEmpty()) {
            payload.put(ATTRS_KEY, new LinkedHashMap<>(attrs));
        }
        return (Json.dumps(payload) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
