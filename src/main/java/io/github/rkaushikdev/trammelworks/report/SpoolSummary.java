package io.github.rkaushikdev.trammelworks.report;

/**
 * What is waiting.
 *
 * @param used bytes on disk
 * @param limit the budget
 * @param segments segments sealed
 * @param evicted segments evicted unsent
 */
public record SpoolSummary(long used, long limit, long segments, long evicted) {

    /** Nothing counted yet, against a budget of one byte. */
    public SpoolSummary() {
        this(0, 1, 0, 0);
    }

    /** How full the spool is, as a fraction. */
    public double ratio() {
        return limit != 0 ? (double) used / limit : 0.0;
    }

    /** Whether the spool has room and lost nothing. */
    public boolean healthy() {
        return ratio() < 0.8 && evicted == 0;
    }

    /** The same, holding {@code value} bytes. */
    public SpoolSummary withUsed(long value) {
        return new SpoolSummary(value, limit, segments, evicted);
    }

    /** The same, against a budget of {@code value} bytes. */
    public SpoolSummary withLimit(long value) {
        return new SpoolSummary(used, value, segments, evicted);
    }

    /** The same, having evicted {@code value} segments. */
    public SpoolSummary withEvicted(long value) {
        return new SpoolSummary(used, limit, segments, value);
    }
}
