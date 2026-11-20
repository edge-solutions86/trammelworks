package io.github.rkaushikdev.trammelworks;

/**
 * What the pipeline is allowed to ask about time.
 *
 * <p>Two readings answer different questions. The wall clock says what time the site controller
 * thinks it is, which is a claim rather than a fact because the RTC drifts and NTP only lands when
 * the LTE modem is up. The monotonic counter says how long since this boot, which is reliable but
 * means nothing across a restart. Everything that has to be reasoned about later takes a clock
 * rather than reading the system time, so a test can drive it and a replay can run against recorded
 * readings.
 */
public interface Clock {

    /** The current wall clock reading, which may be wrong. */
    NanoTime now();

    /** Nanoseconds since an arbitrary point that only moves forward. */
    long monotonicNs();
}
