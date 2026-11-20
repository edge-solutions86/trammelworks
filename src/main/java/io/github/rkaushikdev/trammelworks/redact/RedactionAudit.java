package io.github.rkaushikdev.trammelworks.redact;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the redaction pass removed, counted by rule name.
 *
 * <p>Counts only. The whole point is that nothing which was removed is written down anywhere, so
 * the audit records that a thing happened and never what.
 */
public final class RedactionAudit {

    private long records;
    private long changed;
    private long refused;
    private final Map<String, Long> hits = new LinkedHashMap<>();

    /** Count {@code count} hits for a rule. A count of zero records nothing. */
    public void note(String rule, long count) {
        if (count != 0) {
            hits.merge(rule, count, Long::sum);
        }
    }

    /** Count one hit for a rule. */
    public void note(String rule) {
        note(rule, 1);
    }

    /** Records seen. */
    public long records() {
        return records;
    }

    /** Records whose message changed. */
    public long changed() {
        return changed;
    }

    /** Records refused because the pass failed. */
    public long refused() {
        return refused;
    }

    void countRecord() {
        records++;
    }

    void countChanged() {
        changed++;
    }

    void countRefused() {
        refused++;
    }

    /** Hits by rule name. */
    public Map<String, Long> hits() {
        return java.util.Collections.unmodifiableMap(hits);
    }

    /** Every hit, all rules together. */
    public long totalHits() {
        return hits.values().stream().mapToLong(Long::longValue).sum();
    }

    /** The counts as the nightly summary carries them. */
    public Map<String, Long> asDict() {
        Map<String, Long> merged = new LinkedHashMap<>();
        merged.put("records", records);
        merged.put("changed", changed);
        merged.put("refused", refused);
        merged.putAll(new TreeMap<>(hits));
        return merged;
    }
}
