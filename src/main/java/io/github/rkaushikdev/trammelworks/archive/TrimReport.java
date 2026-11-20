package io.github.rkaushikdev.trammelworks.archive;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** What a trim pass took, and what it could not. */
public final class TrimReport {

    private long examined;
    private long deleted;
    private long held;
    private final Map<String, Long> byClass = new LinkedHashMap<>();
    private String stopped = "";

    /** A report with nothing in it. */
    public TrimReport() {
        this(0, 0, 0, "");
    }

    /** A report with the totals given. */
    public TrimReport(long examined, long deleted, long held, String stopped) {
        this.examined = examined;
        this.deleted = deleted;
        this.held = held;
        this.stopped = stopped;
    }

    /** Rows looked at. */
    public long examined() {
        return examined;
    }

    /** Rows trimmed. */
    public long deleted() {
        return deleted;
    }

    /** Rows kept by a hold. */
    public long held() {
        return held;
    }

    /** Rows trimmed, by class. */
    public Map<String, Long> byClass() {
        return Collections.unmodifiableMap(byClass);
    }

    /** Why the trim stopped, or empty. */
    public String stopped() {
        return stopped;
    }

    /** Whether nothing was trimmed. */
    public boolean idle() {
        return deleted == 0;
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
        deleted += count;
    }

    void stop(String reason) {
        stopped = reason;
    }

    @Override
    public String toString() {
        String end = stopped.isEmpty() ? "" : ", stopped: " + stopped;
        return "trimmed " + deleted + " of " + examined + " examined, " + held + " held" + end;
    }
}
