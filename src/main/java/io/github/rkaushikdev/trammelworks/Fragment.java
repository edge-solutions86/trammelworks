package io.github.rkaushikdev.trammelworks;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * One physical line read out of a container runtime log file.
 *
 * <p>Not the same thing as a {@link LogRecord}: the CRI runtime splits an application line at 16
 * KiB and marks every piece but the last as partial, so a stack trace from the payment kernel
 * arrives as several fragments that must become one record.
 *
 * @param observedAt the timestamp the runtime wrote on the line
 * @param stream the descriptor the container wrote to
 * @param message the line's text, without the runtime's framing
 * @param source where the bytes are
 * @param partial whether the runtime marked the line as continued on the next one
 * @param lossy whether decoding had to replace bytes that were not valid UTF-8
 * @param labels extra facts the reader attached
 */
public record Fragment(
        NanoTime observedAt,
        Stream stream,
        String message,
        SourceRef source,
        boolean partial,
        boolean lossy,
        Map<String, String> labels) {

    /** Copies the labels. */
    public Fragment {
        labels = Maps.frozen(labels);
    }

    /** A complete, clean fragment with no labels. */
    public Fragment(NanoTime observedAt, Stream stream, String message, SourceRef source) {
        this(observedAt, stream, message, source, false, false, Map.of());
    }

    /** A clean fragment with no labels, partial or not. */
    public Fragment(NanoTime observedAt, Stream stream, String message, SourceRef source, boolean partial) {
        this(observedAt, stream, message, source, partial, false, Map.of());
    }

    /** Whether this fragment ends an application line. */
    public boolean complete() {
        return !partial;
    }

    /** For anything that has to talk to a calendar. Loses three digits. */
    public OffsetDateTime toDateTime() {
        return observedAt.toDateTime();
    }

    /** The same fragment with different text. */
    public Fragment withMessage(String text) {
        return new Fragment(observedAt, stream, text, source, partial, lossy, labels);
    }

    /** The same fragment with different labels. */
    public Fragment withLabels(Map<String, String> replacement) {
        return new Fragment(observedAt, stream, message, source, partial, lossy, replacement);
    }
}
