package io.github.rkaushikdev.trammelworks;

import java.util.Locale;

/**
 * One thing the self check learned.
 *
 * @param level how much somebody should care
 * @param check which check found it
 * @param message what it found
 */
public record Finding(Level level, String check, String message) {

    /** How much somebody should care, and so what they do about it. */
    public enum Level {
        /** The site will not work. Fix it now, before leaving. */
        FAIL("fail"),
        /** The site works and something will bite later. Raise a ticket. */
        WARN("warn"),
        /** Worth knowing, needs nothing. */
        NOTE("note");

        private final String value;

        Level(String value) {
            this.value = value;
        }

        /** The level's name. */
        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    @Override
    public String toString() {
        return pad(level.value().toUpperCase(Locale.ROOT), 4) + " " + pad(check, 18) + " " + message;
    }

    private static String pad(String text, int width) {
        int length = text.codePointCount(0, text.length());
        return length >= width ? text : text + " ".repeat(width - length);
    }
}
