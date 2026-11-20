package io.github.rkaushikdev.trammelworks.ship;

/** What a cut produced. */
public final class BatchStats {

    private int batches;
    private long records;
    private int oversized;
    private long bytesPlanned;
    private long largest;

    /** Batches cut. */
    public int batches() {
        return batches;
    }

    /** Records placed in them. */
    public long records() {
        return records;
    }

    /** Records larger than the byte bound on their own. */
    public int oversized() {
        return oversized;
    }

    /** Wire bytes the batches will cost. */
    public long bytesPlanned() {
        return bytesPlanned;
    }

    /** The largest batch on the wire. */
    public long largest() {
        return largest;
    }

    void oversize() {
        oversized++;
    }

    void emitted(Batch batch) {
        batches++;
        records += batch.count();
        long wire = batch.wireBytes();
        bytesPlanned += wire;
        largest = Math.max(largest, wire);
    }
}
