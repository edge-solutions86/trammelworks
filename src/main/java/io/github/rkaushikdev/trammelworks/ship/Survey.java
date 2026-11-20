package io.github.rkaushikdev.trammelworks.ship;

import java.util.List;

/**
 * What a look over the spool for compaction found.
 *
 * @param plans the segments worth rewriting, biggest saving first
 * @param unreadable the segments that would not read
 */
public record Survey(List<CompactionPlan> plans, List<String> unreadable) {

    /** Copies the lists. */
    public Survey {
        plans = List.copyOf(plans);
        unreadable = List.copyOf(unreadable);
    }

    /** What every plan together gives back. */
    public long reclaim() {
        return plans.stream().mapToLong(CompactionPlan::reclaim).sum();
    }
}
