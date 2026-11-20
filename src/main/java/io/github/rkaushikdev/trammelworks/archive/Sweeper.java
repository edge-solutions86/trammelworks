package io.github.rkaushikdev.trammelworks.archive;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.RetentionError;
import io.github.rkaushikdev.trammelworks.Units;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Deletes what retention says may go, and keeps what the holds say may not.
 *
 * <p>Deliberately dull and deliberately bounded: it deletes in batches with a cap per pass, because
 * a site back after a month with four million expired rows must not hold the write lock for forty
 * minutes while the collector writes into the same file. Nothing here decides anything; every
 * question goes to {@link RetentionPolicy}.
 */
public final class Sweeper {

    /** The default per pass delete cap. */
    public static final int DEFAULT_MAX_DELETES = 50_000;

    private final ArchiveDb db;
    private final RetentionPolicy policy;
    private final int maxDeletes;

    /** A sweeper with the default policy and cap. */
    public Sweeper(ArchiveDb db) {
        this(db, new RetentionPolicy(), DEFAULT_MAX_DELETES);
    }

    /** A sweeper with a given policy and cap. */
    public Sweeper(ArchiveDb db, RetentionPolicy policy, int maxDeletes) {
        if (maxDeletes < 1) {
            throw new RetentionError("max_deletes must be at least one");
        }
        this.db = db;
        this.policy = policy;
        this.maxDeletes = maxDeletes;
    }

    /** The holds this sweeper honours. */
    public HoldStore holds() {
        return new HoldStore(db);
    }

    /**
     * Rows old enough to be worth asking about, bounded by the shortest window in the policy: a
     * record may go once it is older than its own class's window, so the earliest anything
     * qualifies is one shortest window ago.
     */
    public List<Row> candidates(NanoTime now, int limit) {
        Optional<Duration> shortest =
                policy.windows().keySet().stream().map(policy::windowFor).min(Duration::compareTo);
        if (shortest.isEmpty()) {
            return List.of();
        }
        NanoTime horizon = now.shifted(-(long) (Units.totalSeconds(shortest.get()) * 1_000_000_000));
        return db.query(
                "SELECT id, klass, " + Repository.ORDER_COLUMN + " AS at FROM records WHERE " + Repository.ORDER_COLUMN
                        + " < ? ORDER BY at ASC LIMIT ?",
                horizon.epochNs(),
                limit);
    }

    /** One bounded pass. */
    public SweepReport sweep(NanoTime now) {
        SweepReport report = new SweepReport();
        List<Hold> holds = holds().all();
        List<Row> rows = candidates(now, maxDeletes);
        List<List<Object>> doomed = new ArrayList<>();
        for (Row row : rows) {
            report.examine();
            String klass = row.getString("klass");
            RetentionDecision found =
                    RetentionPolicy.decide(policy, holds, klass, new NanoTime(row.getLong("at")), now);
            if (found.deletable()) {
                doomed.add(List.of(row.getLong("id")));
                report.note(klass);
            } else {
                report.hold();
            }
        }
        if (!doomed.isEmpty()) {
            db.transaction(() -> db.executeMany("DELETE FROM records WHERE id = ?", doomed));
            report.deleted(doomed.size());
        }
        report.capped(rows.size() >= maxDeletes);
        return report;
    }

    /** Keep sweeping while a pass is still hitting its cap, at most twenty passes. */
    public SweepReport sweepUntilClear(NanoTime now) {
        return sweepUntilClear(now, 20);
    }

    /** Keep sweeping while a pass is still hitting its cap. */
    public SweepReport sweepUntilClear(NanoTime now, int maxPasses) {
        SweepReport total = new SweepReport();
        for (int pass = 0; pass < maxPasses; pass++) {
            SweepReport found = sweep(now);
            total.add(found);
            if (!found.capped() || found.deleted() == 0) {
                break;
            }
            total.capped(true);
        }
        return total;
    }
}
