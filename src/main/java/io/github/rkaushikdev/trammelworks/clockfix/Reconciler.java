package io.github.rkaushikdev.trammelworks.clockfix;

import io.github.rkaushikdev.trammelworks.Clock;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Puts one session's drift correction onto records as they go past.
 *
 * <p>A CRI line carries no monotonic reading, only the wall clock time we do not trust. But that
 * wall clock and the monotonic counter are read from the same box, so the interval between now and
 * the record's timestamp, measured in that clock's own units, very nearly cancels the error:
 * {@code position = monotonic_now - (wall_now - record_wall)}. What survives is the drift across
 * the interval itself. A journald record carries its real position, and that is used instead.
 */
public final class Reconciler {

    /** The label carrying a record's position in its boot session. */
    public static final String MONOTONIC_LABEL = "monotonic_ns";

    /** The label carrying the boot session. */
    public static final String BOOT_LABEL = "boot_id";

    /** The label carrying the correction, in milliseconds. */
    public static final String DRIFT_LABEL = "clock_drift_ms";

    private final SessionLedger ledger;
    private final String bootId;
    private final Clock clock;
    private final ReconcileStats stats = new ReconcileStats();

    /** A reconciler for the session {@code bootId}. */
    public Reconciler(SessionLedger ledger, String bootId, Clock clock) {
        this.ledger = ledger;
        this.bootId = bootId;
        this.clock = clock;
    }

    /** What the pass has done. */
    public ReconcileStats stats() {
        return stats;
    }

    /** The session being corrected. */
    public BootSession session() {
        return ledger.session(bootId);
    }

    /** The drift model for the session as it stands. */
    public DriftModel model() {
        return new DriftModel(session());
    }

    /** Estimate where in this boot session a wall clock reading sits. */
    public long positionOf(NanoTime reading) {
        long elapsed = clock.now().minus(reading);
        return Math.max(0, clock.monotonicNs() - elapsed);
    }

    /** The record with a corrected time and a confidence on it. */
    public LogRecord reconcile(LogRecord record) {
        String stated = record.labels().get(MONOTONIC_LABEL);
        boolean exact = stated != null;
        long position = exact ? Long.parseLong(stated.strip()) : positionOf(record.observedAt());
        Correction correction = model().correctionAt(position);
        stats.note(correction, exact);
        Map<String, String> labels = new LinkedHashMap<>(record.labels());
        if (correction.offsetNs() != 0) {
            labels.put(
                    DRIFT_LABEL, Long.toString((long) Math.rint(correction.offsetNs() / (double) NanoTime.NS_PER_MS)));
        }
        if (correction.stale()) {
            labels.put("clock_stale", "true");
        }
        labels.put(BOOT_LABEL, session().bootId());
        return record.replaceLabels(labels)
                .withCorrection(
                        Optional.of(correction.apply(record.observedAt())),
                        correction.confidence().value());
    }

    /** Correct a batch, keeping its order. */
    public List<LogRecord> reconcileAll(List<LogRecord> records) {
        return records.stream().map(this::reconcile).toList();
    }
}
