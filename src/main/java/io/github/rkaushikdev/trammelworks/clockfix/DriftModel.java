package io.github.rkaushikdev.trammelworks.clockfix;

import io.github.rkaushikdev.trammelworks.NanoTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The correction function for one boot session.
 *
 * <p>The error over a session is a sawtooth, not a curve: a sync does not reduce the error, it
 * removes it. Between two syncs the later one measured the error accumulated since the earlier
 * one, so a point in between gets its share (interpolated). Before the first sync the whole
 * measured error applies flat. After the last one the drift is projected from the last pair's
 * rate, or taken as zero with only one sync (extrapolated), and a projection past the stale window
 * is arithmetic rather than evidence, so it is marked stale.
 *
 * @param session the session modelled
 * @param staleAfterNs how far past the last sync a projection stays fresh
 */
public record DriftModel(BootSession session, long staleAfterNs) {

    /** One part per million. */
    public static final long PPM = 1_000_000;

    /** The default stale window. */
    public static final long DEFAULT_STALE_AFTER_NS = 6 * 3600 * NanoTime.NS_PER_SECOND;

    /** A rate beyond this is not a drifting crystal, it is a broken one. */
    public static final double MAX_PLAUSIBLE_PPM = 10_000.0;

    /** A model with the default stale window. */
    public DriftModel(BootSession session) {
        this(session, DEFAULT_STALE_AFTER_NS);
    }

    /**
     * Error accumulated per nanosecond elapsed, from the last pair of syncs: a crystal's drift
     * changes with temperature, and the most recent pair is the most relevant one.
     */
    public OptionalDouble rate() {
        List<SyncEvent> syncs = session.syncs();
        if (syncs.size() < 2) {
            return OptionalDouble.empty();
        }
        SyncEvent previous = syncs.get(syncs.size() - 2);
        SyncEvent latest = syncs.get(syncs.size() - 1);
        long elapsed = latest.monotonicNs() - previous.monotonicNs();
        if (elapsed <= 0) {
            return OptionalDouble.empty();
        }
        double found = latest.offsetNs() / (double) elapsed;
        if (Math.abs(found) * PPM > MAX_PLAUSIBLE_PPM) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(found);
    }

    /** The same rate in parts per million, which is how crystals are sold. */
    public OptionalDouble ratePpm() {
        OptionalDouble found = rate();
        return found.isPresent() ? OptionalDouble.of(found.getAsDouble() * PPM) : OptionalDouble.empty();
    }

    /** The correction for something observed at this point in the session. */
    public Correction correctionAt(long monotonicNs) {
        BootSession.Bracket bracket = session.bracket(monotonicNs);
        if (bracket.before().isEmpty() && bracket.after().isEmpty()) {
            return Correction.NONE;
        }
        if (bracket.after().isPresent()) {
            return beforeASync(monotonicNs, bracket.before(), bracket.after().get());
        }
        return afterTheLastSync(monotonicNs, bracket.before().get());
    }

    private static Correction beforeASync(long monotonicNs, Optional<SyncEvent> before, SyncEvent after) {
        if (before.isEmpty()) {
            // Nothing to scale against: the error was already there at boot.
            return new Correction(after.offsetNs(), Confidence.EXTRAPOLATED);
        }
        long span = after.monotonicNs() - before.get().monotonicNs();
        if (span <= 0) {
            return new Correction(after.offsetNs(), Confidence.INTERPOLATED);
        }
        double share = (monotonicNs - before.get().monotonicNs()) / (double) span;
        return new Correction((long) Math.rint(after.offsetNs() * share), Confidence.INTERPOLATED);
    }

    private Correction afterTheLastSync(long monotonicNs, SyncEvent before) {
        long elapsed = monotonicNs - before.monotonicNs();
        boolean stale = elapsed > staleAfterNs;
        if (elapsed == 0) {
            return new Correction(0, Confidence.MEASURED);
        }
        OptionalDouble rate = rate();
        if (rate.isEmpty()) {
            return new Correction(0, Confidence.EXTRAPOLATED, stale);
        }
        return new Correction((long) Math.rint(rate.getAsDouble() * elapsed), Confidence.EXTRAPOLATED, stale);
    }
}
