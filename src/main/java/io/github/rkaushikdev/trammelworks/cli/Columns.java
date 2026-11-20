package io.github.rkaushikdev.trammelworks.cli;

/** Fixed width columns for terminal output, counted in characters. */
final class Columns {

    private Columns() {}

    /** {@code text} padded on the right to {@code width}. */
    static String left(Object text, int width) {
        String value = String.valueOf(text);
        int length = value.codePointCount(0, value.length());
        return length >= width ? value : value + " ".repeat(width - length);
    }

    /** {@code text} padded on the left to {@code width}. */
    static String right(Object text, int width) {
        String value = String.valueOf(text);
        int length = value.codePointCount(0, value.length());
        return length >= width ? value : " ".repeat(width - length) + value;
    }
}
