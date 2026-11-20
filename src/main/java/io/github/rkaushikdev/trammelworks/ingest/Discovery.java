package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.LogPathInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What one walk of the log root found.
 *
 * @param follow the files to follow, one per unit
 * @param skipped everything left alone, with why
 * @param refused the confinement verdicts on links out of the root
 */
public record Discovery(List<LogPathInfo> follow, List<Skipped> skipped, List<Verdict> refused) {

    /** Copies the lists. */
    public Discovery {
        follow = List.copyOf(follow);
        skipped = List.copyOf(skipped);
        refused = List.copyOf(refused);
    }

    /** The units followed, sorted. */
    public List<String> units() {
        TreeSet<String> out = new TreeSet<>();
        follow.forEach(info -> out.add(info.unit()));
        return List.copyOf(out);
    }

    /** How many paths were skipped for each reason, in the order the reasons first appeared. */
    public Map<SkipReason, Integer> reasons() {
        Map<SkipReason, Integer> counts = new LinkedHashMap<>();
        for (Skipped entry : skipped) {
            counts.merge(entry.reason(), 1, Integer::sum);
        }
        return counts;
    }
}
