package io.github.rkaushikdev.trammelworks.spool;

import java.util.List;

/**
 * What an eviction would delete, and whether it is enough.
 *
 * @param victims the segments to delete, in order
 * @param needed the bytes asked for
 * @param protectedBytes bytes in protected classes, which no plan may touch
 * @param pinnedBytes bytes in segments being shipped right now
 */
public record EvictionPlan(List<SegmentInfo> victims, long needed, long protectedBytes, long pinnedBytes) {

    /** Copies the victims. */
    public EvictionPlan {
        victims = List.copyOf(victims);
    }

    /** What the plan would free. */
    public long freed() {
        return victims.stream().mapToLong(SegmentInfo::size).sum();
    }

    /** Whether the plan frees what was asked for. */
    public boolean sufficient() {
        return freed() >= needed;
    }

    /** How far short of the need the plan falls. */
    public long shortfall() {
        return Math.max(0, needed - freed());
    }

    /** Whether the plan deletes nothing. */
    public boolean empty() {
        return victims.isEmpty();
    }
}
