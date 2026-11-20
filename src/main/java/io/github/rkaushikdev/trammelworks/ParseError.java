package io.github.rkaushikdev.trammelworks;

import java.util.Map;
import java.util.Optional;

/** A log line did not match the format its reader expected. */
public class ParseError extends StanchionError {

    /** The stable code for this kind of failure. */
    public static final String CODE = "stanchion.parse";

    private final String source;
    private final Long offset;

    /** An error with no detail and no position. */
    public ParseError(String message) {
        this(message, null, null, null);
    }

    /** An error with a detail and no position. */
    public ParseError(String message, String detail) {
        this(message, detail, null, null);
    }

    /**
     * An error with everything known about where it happened. Any of {@code detail}, {@code source}
     * and {@code offset} may be {@code null}.
     */
    public ParseError(String message, String detail, String source, Long offset) {
        super(message, detail);
        this.source = source;
        this.offset = offset;
    }

    /** An error naming the file it was raised for. */
    public static ParseError at(String message, String source) {
        return new ParseError(message, null, source, null);
    }

    @Override
    public String code() {
        return CODE;
    }

    /** The file the line came from, when known. */
    public Optional<String> source() {
        return Optional.ofNullable(source);
    }

    /** The byte offset of the line, when known. */
    public Optional<Long> offset() {
        return Optional.ofNullable(offset);
    }

    @Override
    public Map<String, String> asDict() {
        Map<String, String> out = super.asDict();
        if (source != null) {
            out.put("source", source);
        }
        if (offset != null) {
            out.put("offset", offset.toString());
        }
        return out;
    }
}
