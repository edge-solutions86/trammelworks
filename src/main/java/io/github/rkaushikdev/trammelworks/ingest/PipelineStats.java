package io.github.rkaushikdev.trammelworks.ingest;

/** Per file numbers, reported per unit in the nightly summary. */
public final class PipelineStats {

    private long polls;
    private long fragments;
    private long records;
    private long skipped;
    private long rotations;
    private long truncations;
    private long disappearances;
    private long lostBytes;

    /** Polls made. */
    public long polls() {
        return polls;
    }

    /** Fragments read. */
    public long fragments() {
        return fragments;
    }

    /** Records produced. */
    public long records() {
        return records;
    }

    /** Lines skipped. */
    public long skipped() {
        return skipped;
    }

    /** Rotations by rename seen. */
    public long rotations() {
        return rotations;
    }

    /** Rotations in place seen. */
    public long truncations() {
        return truncations;
    }

    /** Times the file went away. */
    public long disappearances() {
        return disappearances;
    }

    /** Bytes held back and lost when the file was replaced. */
    public long lostBytes() {
        return lostBytes;
    }

    void poll(int fragmentCount, int skippedCount) {
        polls++;
        fragments += fragmentCount;
        skipped += skippedCount;
    }

    void produced(int count) {
        records += count;
    }

    void event(TailEvent event, long lost) {
        switch (event) {
            case ROTATED -> rotations++;
            case TRUNCATED -> truncations++;
            case MISSING -> disappearances++;
            default -> {
                return;
            }
        }
        lostBytes += lost;
    }
}
