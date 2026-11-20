package io.github.rkaushikdev.trammelworks;

/**
 * A monotonic deadline, used by the shipper to bound an attempt.
 *
 * @param atNs the monotonic reading at which the deadline lands
 */
public record Deadline(long atNs) {

    /** A deadline {@code seconds} after the clock's current monotonic reading. */
    public static Deadline after(Clock clock, double seconds) {
        if (seconds < 0) {
            throw new StanchionError("deadline cannot be in the past");
        }
        return new Deadline(clock.monotonicNs() + (long) (seconds * NanoTime.NS_PER_SECOND));
    }

    /** Whether the deadline has landed, inclusive of the boundary. */
    public boolean expired(Clock clock) {
        return clock.monotonicNs() >= atNs;
    }

    /** Seconds left, clamped at zero so a caller can pass it to a timeout. */
    public double remaining(Clock clock) {
        long left = atNs - clock.monotonicNs();
        return Math.max(0.0, left / (double) NanoTime.NS_PER_SECOND);
    }
}
