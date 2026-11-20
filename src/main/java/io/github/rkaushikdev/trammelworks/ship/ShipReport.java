package io.github.rkaushikdev.trammelworks.ship;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What one pass of the shipper did. */
public final class ShipReport {

    private int batchesSent;
    private long recordsSent;
    private long bytesSent;
    private int segmentsCompleted;
    private int segmentsTouched;
    private final List<String> blockedByMeter = new ArrayList<>();
    private double waitSeconds;
    private String stopped = "";
    private int terminal;

    /** A pass that sent nothing. */
    public ShipReport() {}

    /** A report with the totals given, for a caller that counted them itself. */
    public ShipReport(long recordsSent, long bytesSent, String stopped) {
        this.recordsSent = recordsSent;
        this.bytesSent = bytesSent;
        this.stopped = stopped;
    }

    /** Batches accepted. */
    public int batchesSent() {
        return batchesSent;
    }

    /** Records accepted. */
    public long recordsSent() {
        return recordsSent;
    }

    /** Bytes charged to the meter. */
    public long bytesSent() {
        return bytesSent;
    }

    /** Segments finished and retired. */
    public int segmentsCompleted() {
        return segmentsCompleted;
    }

    /** Segments with something left to send. */
    public int segmentsTouched() {
        return segmentsTouched;
    }

    /** Segments whose class the meter would not let ship. */
    public List<String> blockedByMeter() {
        return Collections.unmodifiableList(blockedByMeter);
    }

    /** How long the backoff says to wait before the next pass. */
    public double waitSeconds() {
        return waitSeconds;
    }

    /** Why the pass stopped early, or empty. */
    public String stopped() {
        return stopped;
    }

    /** Batches refused for good. */
    public int terminal() {
        return terminal;
    }

    /** Whether the pass sent nothing and nothing stopped it. */
    public boolean idle() {
        return batchesSent == 0 && stopped.isEmpty();
    }

    void sent(Batch batch) {
        batchesSent++;
        recordsSent += batch.count();
    }

    void charged(long bytes) {
        bytesSent += bytes;
    }

    void completed() {
        segmentsCompleted++;
    }

    void touched() {
        segmentsTouched++;
    }

    void blocked(String segment) {
        blockedByMeter.add(segment);
    }

    void refused() {
        terminal++;
    }

    void stop(String reason, double wait) {
        stopped = reason;
        waitSeconds = wait;
    }
}
