package io.github.rkaushikdev.trammelworks.report;

import io.github.rkaushikdev.trammelworks.StanchionError;

/**
 * What the caller wants the output to look like.
 *
 * @param format the format
 * @param width the widest a message may be in the text and markdown tables
 * @param header whether the text and csv forms start with a header
 * @param rawTime whether to show the time the box claimed rather than the corrected one
 */
public record RenderOptions(ReportFormat format, int width, boolean header, boolean rawTime) {

    /** How wide a message may be by default. */
    public static final int DEFAULT_MESSAGE_WIDTH = 80;

    /** Refuses a width below one. */
    public RenderOptions {
        if (width < 1) {
            throw new StanchionError("message width must be at least one");
        }
    }

    /** Text, eighty wide, with a header, showing corrected times. */
    public RenderOptions() {
        this(ReportFormat.TEXT, DEFAULT_MESSAGE_WIDTH, true, false);
    }

    /** The defaults in another format. */
    public static RenderOptions of(ReportFormat format) {
        return new RenderOptions().withFormat(format);
    }

    /** The same in another format. */
    public RenderOptions withFormat(ReportFormat value) {
        return new RenderOptions(value, width, header, rawTime);
    }

    /** The same with another message width. */
    public RenderOptions withWidth(int value) {
        return new RenderOptions(format, value, header, rawTime);
    }

    /** The same with or without a header. */
    public RenderOptions withHeader(boolean value) {
        return new RenderOptions(format, width, value, rawTime);
    }

    /** The same showing raw or corrected times. */
    public RenderOptions withRawTime(boolean value) {
        return new RenderOptions(format, width, header, value);
    }
}
