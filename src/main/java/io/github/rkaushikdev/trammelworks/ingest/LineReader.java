package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Fragment;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.SourceRef;
import java.util.Map;

/**
 * The shared walk over a newline delimited buffer. The two line formats differ only in how one
 * line is decoded, so each supplies that and the walk lives here.
 *
 * @param parser how one line is decoded
 */
public record LineReader(LineParser parser) implements FragmentReader {

    /** Decodes one line, terminator included. */
    @FunctionalInterface
    public interface LineParser {
        /** Parse a line that started at {@code offset} in {@code source}. */
        ParsedLine parse(byte[] raw, String source, long offset);
    }

    /** Reads the CRI text format. */
    public static LineReader cri() {
        return new LineReader(CriLine::parse);
    }

    /** Reads the docker json-file format. */
    public static LineReader jsonFile() {
        return new LineReader(JsonFileLine::parse);
    }

    @Override
    public ReadOutcome read(byte[] data, String path, long baseOffset) {
        ReadOutcome result = new ReadOutcome();
        int start = 0;
        while (true) {
            int end = Bytes.indexOf(data, (byte) '\n', start);
            if (end < 0) {
                return result;
            }
            byte[] raw = Bytes.slice(data, start, end + 1);
            int offset = start;
            start = end + 1;
            result.consumedTo(start);
            if (Bytes.isBlank(raw)) {
                result.skip();
                continue;
            }
            ParsedLine line;
            try {
                line = parser.parse(raw, path, baseOffset + offset);
            } catch (ParseError exc) {
                result.skip(exc);
                continue;
            }
            result.add(new Fragment(
                    line.timestamp(),
                    line.stream(),
                    line.message(),
                    new SourceRef(path, baseOffset + offset, raw.length),
                    line.partial(),
                    line.lossy(),
                    Map.of()));
        }
    }
}
