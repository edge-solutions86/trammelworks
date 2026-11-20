package io.github.rkaushikdev.trammelworks.clockfix;

/** What the correction pass did, for the nightly summary. */
public final class ReconcileStats {

    private long seen;
    private long corrected;
    private long measured;
    private long interpolated;
    private long extrapolated;
    private long unknown;
    private long stale;
    private long fromMonotonic;

    /** Count one correction. */
    public void note(Correction correction, boolean exactPosition) {
        seen++;
        if (correction.offsetNs() != 0) {
            corrected++;
        }
        if (exactPosition) {
            fromMonotonic++;
        }
        if (correction.stale()) {
            stale++;
        }
        switch (correction.confidence()) {
            case MEASURED -> measured++;
            case INTERPOLATED -> interpolated++;
            case EXTRAPOLATED -> extrapolated++;
            case UNKNOWN -> unknown++;
        }
    }

    /** Records seen. */
    public long seen() {
        return seen;
    }

    /** Records whose time moved. */
    public long corrected() {
        return corrected;
    }

    /** Records at a sync. */
    public long measured() {
        return measured;
    }

    /** Records between two syncs. */
    public long interpolated() {
        return interpolated;
    }

    /** Records projected from a sync. */
    public long extrapolated() {
        return extrapolated;
    }

    /** Records in a session with no sync. */
    public long unknown() {
        return unknown;
    }

    /** Records whose projection was past the stale window. */
    public long stale() {
        return stale;
    }

    /** Records that carried their own monotonic position. */
    public long fromMonotonic() {
        return fromMonotonic;
    }
}
