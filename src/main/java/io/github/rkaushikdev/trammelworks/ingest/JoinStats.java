package io.github.rkaushikdev.trammelworks.ingest;

/** Numbers the nightly summary reports about reassembly, and support asks about. */
public final class JoinStats {

    private long fragments;
    private long records;
    private long joined;
    private long truncated;
    private long droppedChunks;
    private long flushedOpen;

    /** Fragments fed in. */
    public long fragments() {
        return fragments;
    }

    /** Records produced. */
    public long records() {
        return records;
    }

    /** Records joined from more than one fragment. */
    public long joined() {
        return joined;
    }

    /** Records cut at a cap or flushed unfinished. */
    public long truncated() {
        return truncated;
    }

    /** Chunks dropped past a cap. */
    public long droppedChunks() {
        return droppedChunks;
    }

    /** Records flushed because their file ended. */
    public long flushedOpen() {
        return flushedOpen;
    }

    void fragment() {
        fragments++;
    }

    void record() {
        records++;
    }

    void join() {
        joined++;
    }

    void truncate() {
        truncated++;
    }

    void drop() {
        droppedChunks++;
    }

    void flushOpen() {
        flushedOpen++;
    }
}
