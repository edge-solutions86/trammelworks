package io.github.rkaushikdev.trammelworks.ship;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Cutting a segment's records into batches the uplink can carry. */
public final class Batches {

    private Batches() {}

    /** What one record costs inside a batch, separator included. */
    public static int recordSize(Map<String, ?> record) {
        return Batch.canonical(record).length + 1;
    }

    /** Cut with the default limits. */
    public static List<Batch> cut(String site, List<Map<String, Object>> records) {
        return cut(site, records, new BatchLimits(), "", new BatchStats());
    }

    /** Cut with given limits. */
    public static List<Batch> cut(String site, List<Map<String, Object>> records, BatchLimits limits) {
        return cut(site, records, limits, "", new BatchStats());
    }

    /**
     * Cut records into batches, in order. A single record larger than the byte bound still goes,
     * on its own: the reason a record is that big is usually a stack trace.
     */
    public static List<Batch> cut(
            String site, List<Map<String, Object>> records, BatchLimits limits, String source, BatchStats stats) {
        List<Batch> out = new ArrayList<>();
        List<Map<String, Object>> held = new ArrayList<>();
        long size = 0;
        int index = 0;
        for (Map<String, Object> record : records) {
            int cost = recordSize(record);
            if (!held.isEmpty() && (size + cost > limits.maxBytes() || held.size() >= limits.maxRecords())) {
                out.add(emit(site, held, source, index - held.size(), stats));
                held = new ArrayList<>();
                size = 0;
            }
            if (cost > limits.maxBytes()) {
                stats.oversize();
            }
            held.add(record);
            size += cost;
            index++;
        }
        if (!held.isEmpty()) {
            out.add(emit(site, held, source, index - held.size(), stats));
        }
        return out;
    }

    private static Batch emit(
            String site, List<Map<String, Object>> held, String source, int firstIndex, BatchStats stats) {
        Batch batch = new Batch(site, held, source, firstIndex);
        stats.emitted(batch);
        return batch;
    }
}
