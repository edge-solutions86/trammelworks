package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Bytes;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.Stream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One physical CRI line, as the runtime wrote it: {@code <rfc3339nano> <stream> <tags> <message>},
 * with exactly three single space separators and everything after the third being the message.
 *
 * <p>Parsing works on bytes: when a dispenser browns out the pump bridge prints the raw RS-485
 * frame, so some lines on a live forecourt are not valid UTF-8. The message is decoded with
 * replacement and the line says that it was.
 *
 * @param timestamp the runtime's timestamp
 * @param stream the descriptor written to
 * @param message everything after the third separator
 * @param partial whether the tag field said the line continues
 * @param tags any further tags after the first
 * @param lossy whether the message had invalid UTF-8 replaced
 */
public record CriLine(
        NanoTime timestamp, Stream stream, String message, boolean partial, List<String> tags, boolean lossy)
        implements ParsedLine {

    /** The tag of a line that is complete. */
    public static final String FULL_TAG = "F";

    /** The tag of a chunk that continues. */
    public static final String PARTIAL_TAG = "P";

    private static final int FIELD_COUNT = 3;

    /** Copies the tags. */
    public CriLine {
        tags = List.copyOf(tags);
    }

    /** A complete line with no extra tags. */
    public CriLine(NanoTime timestamp, Stream stream, String message) {
        this(timestamp, stream, message, false, List.of(), false);
    }

    /** Whether this line ends an application line. */
    public boolean complete() {
        return !partial;
    }

    /** The partial flag and any further tags of a tag field. */
    public record Tags(boolean partial, List<String> extra) {}

    /** Split the tag field into the partial flag and whatever else is there. */
    public static Tags parseTags(String raw) {
        List<String> parts = new ArrayList<>();
        for (String part : raw.split(":", -1)) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            throw new ParseError("empty cri tag field");
        }
        String head = parts.get(0);
        List<String> rest = List.copyOf(parts.subList(1, parts.size()));
        if (head.equals(FULL_TAG)) {
            return new Tags(false, rest);
        }
        if (head.equals(PARTIAL_TAG)) {
            return new Tags(true, rest);
        }
        throw new ParseError("unknown cri tag", raw);
    }

    /** Parse one CRI line with no position to report. */
    public static CriLine parse(byte[] raw) {
        return parse(raw, null, 0);
    }

    /** Parse one CRI line, terminator optional. */
    public static CriLine parse(byte[] raw, String source, long offset) {
        byte[] body = Bytes.stripLineEnd(raw);
        if (body.length == 0) {
            throw new ParseError("empty cri line", null, source, offset);
        }
        List<byte[]> fields = Bytes.split(body, (byte) ' ', FIELD_COUNT);
        if (fields.size() < FIELD_COUNT) {
            throw new ParseError("cri line has too few fields", null, source, offset);
        }
        byte[] messageRaw = fields.size() > FIELD_COUNT ? fields.get(3) : new byte[0];
        NanoTime timestamp;
        try {
            timestamp = NanoTime.parse(ascii(fields.get(0)));
        } catch (ParseError exc) {
            throw new ParseError("cri line has no timestamp", null, source, offset);
        }
        Stream stream;
        try {
            stream = Stream.parse(ascii(fields.get(1)));
        } catch (IllegalArgumentException | ParseError exc) {
            throw new ParseError("cri line has no stream", null, source, offset);
        }
        Tags tags;
        try {
            tags = parseTags(ascii(fields.get(2)));
        } catch (ParseError exc) {
            throw new ParseError("cri line has a bad tag", null, source, offset);
        }
        Bytes.Decoded message = Bytes.decode(messageRaw);
        return new CriLine(timestamp, stream, message.text(), tags.partial(), tags.extra(), message.lossy());
    }

    private static String ascii(byte[] raw) {
        if (!Bytes.isAscii(raw)) {
            throw new ParseError("not ascii");
        }
        return new String(raw, StandardCharsets.US_ASCII);
    }

    /** Render back to the wire form, terminator included. */
    public byte[] render() {
        StringBuilder tag = new StringBuilder(partial ? PARTIAL_TAG : FULL_TAG);
        for (String extra : tags) {
            tag.append(':').append(extra);
        }
        String line = timestamp.isoformat() + " " + stream.value() + " " + tag + " " + message + "\n";
        return line.getBytes(StandardCharsets.UTF_8);
    }
}
