package io.github.rkaushikdev.trammelworks.ingest;

/**
 * What a single reassembled line is allowed to cost. A dispenser stuck in a reset loop has printed
 * a single 90 MB line, so there is a cap, and a line cut at it says so.
 *
 * @param maxLineBytes the most UTF-8 bytes one record may hold
 * @param maxChunks the most chunks one record may be joined from
 */
public record JoinLimits(long maxLineBytes, int maxChunks) {

    /** The default byte cap. */
    public static final long DEFAULT_MAX_LINE_BYTES = 1024L * 1024;

    /** The default chunk cap. */
    public static final int DEFAULT_MAX_CHUNKS = 4096;

    /** Refuses a non-positive cap. */
    public JoinLimits {
        if (maxLineBytes <= 0) {
            throw new IllegalArgumentException("max_line_bytes must be positive");
        }
        if (maxChunks <= 0) {
            throw new IllegalArgumentException("max_chunks must be positive");
        }
    }

    /** The defaults. */
    public JoinLimits() {
        this(DEFAULT_MAX_LINE_BYTES, DEFAULT_MAX_CHUNKS);
    }

    /** A byte cap with the default chunk cap. */
    public JoinLimits(long maxLineBytes) {
        this(maxLineBytes, DEFAULT_MAX_CHUNKS);
    }
}
