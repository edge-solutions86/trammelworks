package io.github.rkaushikdev.trammelworks.query;

import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** What a search found, and what it had to do to find it. */
public final class SearchResult {

    private final List<StoredRecord> records = new ArrayList<>();
    private final String planSql;
    private long scanned;
    private long postFiltered;
    private boolean truncated;
    private Cursor nextCursor;

    SearchResult(String planSql) {
        this.planSql = planSql;
    }

    /** The matching records, in archive order. */
    public List<StoredRecord> records() {
        return Collections.unmodifiableList(records);
    }

    /** Rows read from the archive. */
    public long scanned() {
        return scanned;
    }

    /** Rows read that the residual dropped. */
    public long postFiltered() {
        return postFiltered;
    }

    /** Whether the read stopped at the scan ceiling. */
    public boolean truncated() {
        return truncated;
    }

    /** The statement that was run. */
    public String planSql() {
        return planSql;
    }

    /** Where the next page starts, when there may be one. */
    public Optional<Cursor> nextCursor() {
        return Optional.ofNullable(nextCursor);
    }

    /** How many records were found. */
    public int count() {
        return records.size();
    }

    /**
     * Whether asking again with the next cursor could return anything: a page that filled its
     * limit, or one that stopped at the ceiling. A page that ran out of rows has no next cursor at
     * all, which is how a caller knows to stop rather than by comparing counts.
     */
    public boolean more() {
        return nextCursor != null;
    }

    /** One line for the terminal. */
    public String describe() {
        return count() + " records from " + scanned + " scanned" + (truncated ? ", truncated" : "");
    }

    void scan() {
        scanned += 1;
    }

    void drop() {
        postFiltered += 1;
    }

    void keep(StoredRecord record, Cursor position) {
        records.add(record);
        nextCursor = position;
    }

    void finish(boolean stoppedAtCeiling) {
        truncated = stoppedAtCeiling;
        if (!truncated) {
            // The rows ran out, so there is no next page.
            nextCursor = null;
        }
    }
}
