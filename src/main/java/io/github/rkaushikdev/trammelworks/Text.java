package io.github.rkaushikdev.trammelworks;

/**
 * Whitespace the way site configs and log lines were trimmed when they were written: the Unicode
 * spaces, the line and paragraph separators and the four ASCII information separators. Java's own
 * {@link String#strip()} keeps the no-break spaces (U+00A0, U+2007, U+202F) and U+0085, which a
 * config copied out of a spreadsheet carries more often than anyone would like.
 */
public final class Text {

    private Text() {}

    /** Whether a code point counts as whitespace. */
    public static boolean isSpace(int point) {
        return switch (point) {
            case '\t',
                    '\n',
                    0x0b,
                    0x0c,
                    '\r',
                    0x1c,
                    0x1d,
                    0x1e,
                    0x1f,
                    ' ',
                    0x85,
                    0xa0,
                    0x1680,
                    0x2028,
                    0x2029,
                    0x202f,
                    0x205f,
                    0x3000 -> true;
            default -> point >= 0x2000 && point <= 0x200a;
        };
    }

    /** The text without leading or trailing whitespace. */
    public static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && isSpace(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(start, end);
    }
}
