package io.github.rkaushikdev.trammelworks.clockfix;

import io.github.rkaushikdev.trammelworks.NanoTime;

/**
 * What to add to a clock reading, and how much to believe it.
 *
 * @param offsetNs the correction
 * @param confidence how it was arrived at
 * @param stale whether it is a projection too far past the last sync to be evidence
 */
public record Correction(long offsetNs, Confidence confidence, boolean stale) {

    /** No correction, because nothing is known. */
    public static final Correction NONE = new Correction(0, Confidence.UNKNOWN);

    /** A correction that is not stale. */
    public Correction(long offsetNs, Confidence confidence) {
        this(offsetNs, confidence, false);
    }

    /** True when the correction rests on a measurement, not a projection. */
    public boolean trusted() {
        return confidence == Confidence.MEASURED || confidence == Confidence.INTERPOLATED;
    }

    /** The corrected reading. */
    public NanoTime apply(NanoTime reading) {
        return reading.shifted(offsetNs);
    }
}
