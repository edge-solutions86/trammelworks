package io.github.rkaushikdev.trammelworks.query;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.QueryError;
import io.github.rkaushikdev.trammelworks.Text;
import java.math.BigInteger;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the last page stopped: the sort key of the last record handed out.
 *
 * <p>Paging through an archive that is still being written to is by key rather than by offset. An
 * offset counts rows from the start, so a record inserted ahead of the window shifts everything down
 * by one and page two begins with the line page one ended with; and {@code OFFSET 400000} makes
 * SQLite walk four hundred thousand rows to throw them away, on an SD card, while the site is
 * writing. The key is the corrected time and the row id to break ties: two records really do share
 * a nanosecond when a container writes a burst.
 *
 * <p>The cursor is opaque by convention rather than by encryption. There is nothing secret in it,
 * and an engineer reading one in a support log is exactly when it needs to be readable.
 *
 * @param atNs the corrected time of the last record returned
 * @param rowId its row id
 */
public record Cursor(long atNs, long rowId) {

    /** The written form: nanoseconds, a dot, the row id. */
    public static final Pattern CURSOR_PATTERN = Pattern.compile("(?<at>-?\\p{Nd}{1,20})\\.(?<row>\\p{Nd}{1,19})");

    /** Refuses a negative row. */
    public Cursor {
        if (rowId < 0) {
            throw new QueryError("a cursor row cannot be negative", Long.toString(rowId));
        }
    }

    /** Read a cursor back, refusing anything that is not one. */
    public static Cursor parseCursor(String raw) {
        Matcher match = CURSOR_PATTERN.matcher(Text.strip(raw));
        if (!match.matches()) {
            throw malformed(raw);
        }
        BigInteger at = new BigInteger(match.group("at"));
        BigInteger row = new BigInteger(match.group("row"));
        if (at.bitLength() > 63 || row.bitLength() > 63) {
            throw malformed(raw);
        }
        return new Cursor(at.longValue(), row.longValue());
    }

    private static QueryError malformed(String raw) {
        StringBuilder shown = new StringBuilder();
        raw.codePoints().limit(64).forEach(shown::appendCodePoint);
        return new QueryError("cursor is malformed", shown.toString());
    }

    /**
     * The predicate that resumes after a cursor, written as the expanded comparison rather than as
     * a row value comparison, because {@code (a, b) > (?, ?)} is not available on every SQLite a
     * site image has shipped with.
     */
    public static String cursorSql(String column) {
        return "((" + column + " > ?) OR (" + column + " = ? AND id > ?))";
    }

    /** The three values {@link #cursorSql} expects, in order. */
    public static List<Object> cursorParams(Cursor cursor) {
        return List.of(cursor.atNs, cursor.atNs, cursor.rowId);
    }

    /** The written form. */
    public String encode() {
        return atNs + "." + rowId;
    }

    /** The instant the cursor names. */
    public NanoTime at() {
        return new NanoTime(atNs);
    }

    @Override
    public String toString() {
        return encode();
    }
}
