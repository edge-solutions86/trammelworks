package io.github.rkaushikdev.trammelworks.archive;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.RetentionError;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Takes records the retention policy would have kept, when space runs out. The unpleasant one, so
 * it goes last, by class and oldest first, and never touches a held record. A site at its cap with
 * nothing but held payment records left stops trimming and says so.
 */
public final class Trimmer {

    private final ArchiveDb db;
    private final Path path;
    private final long capBytes;
    private final PriorityTable table;
    private final int batch;

    /** A trimmer with the built in classes and the default batch. */
    public Trimmer(ArchiveDb db, Path path, long capBytes) {
        this(db, path, capBytes, new PriorityTable(), Space.DEFAULT_TRIM_BATCH);
    }

    /** A trimmer with everything given. */
    public Trimmer(ArchiveDb db, Path path, long capBytes, PriorityTable table, int batch) {
        if (capBytes <= 0) {
            throw new RetentionError("the archive cap must be positive", Long.toString(capBytes));
        }
        if (batch < 1) {
            throw new RetentionError("the trim batch must be at least one");
        }
        this.db = db;
        this.path = path;
        this.capBytes = capBytes;
        this.table = table;
        this.batch = batch;
    }

    /** The archive's footprint now. */
    public Footprint footprint() {
        return Space.measure(db, path);
    }

    /** How far the live pages are over the cap. */
    public long overBy() {
        return Math.max(footprint().used() - capBytes, 0);
    }

    /**
     * Which class gets trimmed first: the spool's eviction order, the same question about a
     * different disk, so the two cannot disagree about what matters. Protected classes are left
     * out entirely, so running off the end of the list cannot delete one.
     */
    public List<String> order() {
        return table.evictOrder();
    }

    /** The oldest rows of a class. */
    public List<Row> candidates(String klass, int limit) {
        return db.query(
                "SELECT id, klass, " + Repository.ORDER_COLUMN
                        + " AS at FROM records WHERE klass = ? ORDER BY at ASC LIMIT ?",
                klass,
                limit);
    }

    /** Trim until the archive is under its cap, or until nothing may go. */
    public TrimReport run(List<Hold> holds, NanoTime now) {
        TrimReport report = new TrimReport();
        if (overBy() == 0) {
            report.stop("already under the cap");
            return report;
        }
        for (String klass : order()) {
            while (overBy() != 0) {
                List<Row> rows = candidates(klass, batch);
                if (rows.isEmpty() || !trimRows(rows, holds, report)) {
                    break;
                }
            }
        }
        if (overBy() != 0) {
            report.stop("nothing left that may be deleted");
        }
        return report;
    }

    private boolean trimRows(List<Row> rows, List<Hold> holds, TrimReport report) {
        List<List<Object>> doomed = new ArrayList<>();
        for (Row row : rows) {
            report.examine();
            NanoTime when = new NanoTime(row.getLong("at"));
            if (holds.stream().anyMatch(hold -> hold.covers(when))) {
                report.hold();
                continue;
            }
            doomed.add(List.of(row.getLong("id")));
            report.note(row.getString("klass"));
        }
        if (doomed.isEmpty()) {
            return false;
        }
        db.transaction(() -> db.executeMany("DELETE FROM records WHERE id = ?", doomed));
        report.deleted(doomed.size());
        return true;
    }
}
