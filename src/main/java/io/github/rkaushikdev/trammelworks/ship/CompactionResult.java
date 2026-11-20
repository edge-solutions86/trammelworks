package io.github.rkaushikdev.trammelworks.ship;

import java.util.List;

/**
 * What one compaction pass did.
 *
 * @param segments segments rewritten
 * @param framesDropped acknowledged records removed
 * @param reclaimed bytes given back
 * @param failed segments that could not be compacted
 */
public record CompactionResult(int segments, long framesDropped, long reclaimed, List<String> failed) {

    /** Copies the failures. */
    public CompactionResult {
        failed = List.copyOf(failed);
    }

    /** A pass that did nothing. */
    public CompactionResult() {
        this(0, 0, 0, List.of());
    }

    /** Whether nothing was rewritten and nothing failed. */
    public boolean idle() {
        return segments == 0 && failed.isEmpty();
    }

    @Override
    public String toString() {
        String end = failed.isEmpty() ? "" : ", " + failed.size() + " failed";
        return "compacted " + segments + " segments, dropped " + framesDropped + " records, freed " + reclaimed
                + " bytes" + end;
    }
}
