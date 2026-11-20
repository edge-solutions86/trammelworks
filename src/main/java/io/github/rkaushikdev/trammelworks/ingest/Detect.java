package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Layout;
import io.github.rkaushikdev.trammelworks.LogPaths;
import io.github.rkaushikdev.trammelworks.ParseError;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deciding which format a file is in.
 *
 * <p>The path says what the runtime intended and the bytes say what it actually wrote, and on a
 * site upgraded in place those disagree: a box migrated from docker to containerd keeps the old
 * {@code /var/lib/docker/containers} tree with the files rewritten in CRI form. So content decides,
 * and the path is only a tie breaker for a sample too short to be sure about.
 */
public final class Detect {

    /** How many leading bytes a file is judged on. */
    public static final int SAMPLE_BYTES = 8192;

    static final Pattern CRI_PREFIX =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[Tt ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?\\S* (stdout|stderr) ");
    static final Pattern JOURNAL_FIELD = Pattern.compile("^[A-Z_][A-Z0-9_]*(=|\n)");

    private Detect() {}

    /** The sample up to its first newline. */
    public static byte[] firstLine(byte[] sample) {
        int end = Bytes.indexOf(sample, (byte) '\n', 0);
        return end < 0 ? sample : Bytes.slice(sample, 0, end);
    }

    static boolean looksLikeJsonfile(byte[] line) {
        byte[] stripped = Bytes.strip(line);
        if (stripped.length == 0 || stripped[0] != '{') {
            return false;
        }
        Object payload;
        try {
            payload = Json.loads(Bytes.decodeReplacing(stripped));
        } catch (Json.JsonException exc) {
            return false;
        }
        return payload instanceof Map<?, ?> map && map.containsKey("log") && map.containsKey("time");
    }

    static boolean looksLikeCri(byte[] line) {
        return CRI_PREFIX.matcher(Bytes.latin1(line)).lookingAt();
    }

    static boolean looksLikeJournal(byte[] sample) {
        byte[] line = firstLine(sample);
        if (!JOURNAL_FIELD.matcher(Bytes.latin1(line) + "\n").lookingAt()) {
            return false;
        }
        return Bytes.contains(line, (byte) '=') || Bytes.contains(sample, (byte) '\n');
    }

    /** Decide from content alone, or empty if the sample says nothing. */
    public static Optional<Format> sniff(byte[] sample) {
        byte[] line = firstLine(sample);
        if (Bytes.isBlank(line)) {
            return Optional.empty();
        }
        if (looksLikeJsonfile(line)) {
            return Optional.of(Format.JSONFILE);
        }
        if (looksLikeCri(line)) {
            return Optional.of(Format.CRI);
        }
        if (looksLikeJournal(sample)) {
            return Optional.of(Format.JOURNAL);
        }
        return Optional.empty();
    }

    /** Decide the format of one file, preferring what the bytes say. */
    public static Detection detect(String path, byte[] sample) {
        Optional<Layout> layout;
        try {
            layout = Optional.of(LogPaths.parseLogPath(path).layout());
        } catch (ParseError exc) {
            layout = Optional.empty();
        }
        Optional<Format> found = sniff(sample);
        if (found.isPresent()) {
            return new Detection(found.get(), true, layout);
        }
        if (layout.isPresent()) {
            return new Detection(Detection.EXPECTED_BY_LAYOUT.get(layout.get()), false, layout);
        }
        throw ParseError.at("cannot tell what format this file is", path);
    }
}
