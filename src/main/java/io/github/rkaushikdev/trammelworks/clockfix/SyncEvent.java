package io.github.rkaushikdev.trammelworks.clockfix;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.NanoTime;

/**
 * One moment the wall clock was corrected: the only direct measurement of the error we ever get.
 *
 * <p>{@code before} is what the clock read at that instant and {@code after} is what it was set to.
 * The difference is the error the clock had accumulated, which every record written earlier in the
 * session is carrying.
 *
 * @param bootId the session it happened in
 * @param monotonicNs where in that session
 * @param before what the clock said
 * @param after what it was set to
 * @param source who corrected it
 */
public record SyncEvent(String bootId, long monotonicNs, NanoTime before, NanoTime after, String source) {

    /** A correction larger than this is a clock that was wrong by more than a shift. */
    public static final long LARGE_CORRECTION_NS = 3600 * NanoTime.NS_PER_SECOND;

    /** Normalizes the boot id and refuses a negative position. */
    public SyncEvent {
        bootId = BootSession.parseBootId(bootId);
        if (monotonicNs < 0) {
            throw new ConfigError("negative monotonic reading", Long.toString(monotonicNs));
        }
    }

    /** A correction made by NTP. */
    public SyncEvent(String bootId, long monotonicNs, NanoTime before, NanoTime after) {
        this(bootId, monotonicNs, before, after, "ntp");
    }

    /** What to add to a clock reading to get the truth, at this instant. */
    public long offsetNs() {
        return after.minus(before);
    }

    /** Whether the correction was an hour or more either way. */
    public boolean large() {
        return Math.abs(offsetNs()) >= LARGE_CORRECTION_NS;
    }

    /** True when the clock was behind and had to be moved on. */
    public boolean forwards() {
        return offsetNs() > 0;
    }
}
