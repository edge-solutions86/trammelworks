package io.github.rkaushikdev.trammelworks;

/** What one collect pass moved. */
public final class CollectReport {

    private long read;
    private long shippedToSpool;
    private long archived;
    private long redacted;
    private long syncsSeen;
    private long refused;

    /** A pass that moved nothing. */
    public CollectReport() {}

    /** A report with the totals given, for a caller that counted them itself. */
    public CollectReport(long read, long shippedToSpool, long redacted) {
        this.read = read;
        this.shippedToSpool = shippedToSpool;
        this.redacted = redacted;
    }

    /** Records read. */
    public long read() {
        return read;
    }

    /** Records that reached the spool. */
    public long shippedToSpool() {
        return shippedToSpool;
    }

    /** Records kept in the archive. */
    public long archived() {
        return archived;
    }

    /** Records redaction changed. */
    public long redacted() {
        return redacted;
    }

    /** Clock corrections filed so far. */
    public long syncsSeen() {
        return syncsSeen;
    }

    /** Records the spool refused. */
    public long refused() {
        return refused;
    }

    /** Whether nothing was read. */
    public boolean idle() {
        return read == 0;
    }

    void read(long value) {
        read = value;
    }

    void spooled() {
        shippedToSpool += 1;
    }

    void archived(long value) {
        archived += value;
    }

    void redactedOne() {
        redacted += 1;
    }

    void syncsSeen(long value) {
        syncsSeen = value;
    }

    void refusedOne() {
        refused += 1;
    }
}
