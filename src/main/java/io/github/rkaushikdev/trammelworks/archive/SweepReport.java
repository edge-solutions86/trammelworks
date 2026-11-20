package io.github.rkaushikdev.trammelworks.archive;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** What one retention sweep did, and what it left. */
public final class SweepReport {

    private long examined;
    private long deleted;
    private long held;
    private final Map<String, Long> byClass = new LinkedHashMap<>();
    private boolean capped;

    /** Rows looked at. */
    public long examined() {
        return examined;
    }

    /** Rows deleted. */
    public long deleted() {
        return deleted;
    }

    /** Rows kept by a hold or their window. */
    public long held() {
        return held;
    }

    /** Rows deleted, by class. */
    public Map<String, Long> byClass() {
        return Collections.unmodifiableMap(byClass);
    }

    /** Whether the pass stopped at its delete cap. */
    public boolean capped() {
        return capped;
    }

    void examine() {
        examined++;
    }

    void hold() {
        held++;
    }

    void note(String klass) {
        byClass.merge(klass, 1L, Long::sum);
    }

    void deleted(long count) {
        deleted = count;
    }

    void capped(boolean value) {
        capped = value;
    }

    void add(SweepReport pass) {
        examined += pass.examined;
        deleted += pass.deleted;
        held += pass.held;
        pass.byClass.forEach((klass, count) -> byClass.merge(klass, count, Long::sum));
    }

    @Override
    public String toString() {
        return "swept " + examined + " rows, deleted " + deleted + ", held " + held + (capped ? ", capped" : "");
    }
}
