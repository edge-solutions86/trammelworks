package io.github.rkaushikdev.trammelworks;

import io.github.rkaushikdev.trammelworks.archive.Footprint;
import io.github.rkaushikdev.trammelworks.archive.SweepReport;
import io.github.rkaushikdev.trammelworks.archive.TrimReport;

/**
 * What one pass over the archive's disk did.
 *
 * <p>Three stages in one report because they only make sense together: the sweep decides what may
 * go, the trim takes more when the cap says it has to, and the vacuum is the only one that hands
 * anything back to the card. Reporting them apart would let an operator read a large sweep as a large
 * saving.
 */
public final class HousekeepReport {

    private SweepReport sweep = new SweepReport();
    private TrimReport trim = new TrimReport();
    private long reclaimed;
    private Footprint before = new Footprint();
    private Footprint after = new Footprint();

    /** A pass that did nothing. */
    public HousekeepReport() {}

    /** A report with the stages given, for a caller that ran them itself. */
    public HousekeepReport(SweepReport sweep, TrimReport trim, long reclaimed) {
        this.sweep = sweep;
        this.trim = trim;
        this.reclaimed = reclaimed;
    }

    /** What retention removed. */
    public SweepReport sweep() {
        return sweep;
    }

    /** What the cap removed. */
    public TrimReport trim() {
        return trim;
    }

    /** Bytes the vacuum gave back. */
    public long reclaimed() {
        return reclaimed;
    }

    /** The archive before the pass. */
    public Footprint before() {
        return before;
    }

    /** The archive after the pass. */
    public Footprint after() {
        return after;
    }

    /** Whether nothing was deleted and nothing given back. */
    public boolean idle() {
        return sweep.deleted() == 0 && trim.deleted() == 0 && reclaimed == 0;
    }

    /** Records removed by either step, which is what an operator asks. */
    public long deleted() {
        return sweep.deleted() + trim.deleted();
    }

    void sweep(SweepReport value) {
        sweep = value;
    }

    void trim(TrimReport value) {
        trim = value;
    }

    void reclaimed(long value) {
        reclaimed = value;
    }

    void before(Footprint value) {
        before = value;
    }

    void after(Footprint value) {
        after = value;
    }

    @Override
    public String toString() {
        return "swept " + sweep.deleted() + ", trimmed " + trim.deleted() + ", freed " + reclaimed + " bytes";
    }
}
