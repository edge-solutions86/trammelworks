package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.spool.SegmentInfo;

/**
 * One segment worth rewriting without its acknowledged prefix, and what it would give back.
 *
 * @param segment the segment
 * @param drop frames acknowledged, which go
 * @param keep frames still to ship, which stay
 * @param reclaim bytes the dropped frames occupy
 */
public record CompactionPlan(SegmentInfo segment, int drop, int keep, long reclaim) {

    /** The segment's file name. */
    public String name() {
        return segment.name();
    }

    /** How much of the file goes. */
    public double fraction() {
        return segment.size() != 0 ? reclaim / (double) segment.size() : 0.0;
    }

    @Override
    public String toString() {
        return name() + " drop " + drop + " keep " + keep + " frees " + reclaim + " bytes";
    }
}
