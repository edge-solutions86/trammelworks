package io.github.rkaushikdev.trammelworks.query;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Groups counted, and how they were arrived at. */
public final class Tally {

    private List<Group> groups;
    private final boolean inDatabase;
    private long scanned;
    private long postFiltered;
    private boolean truncated;

    /** An empty tally counted in the archive. */
    public Tally() {
        this(List.of(), true);
    }

    /** A tally of {@code groups}, counted in the archive or in memory. */
    public Tally(List<Group> groups, boolean inDatabase) {
        this.groups = List.copyOf(groups);
        this.inDatabase = inDatabase;
    }

    /** The groups, biggest first. */
    public List<Group> groups() {
        return Collections.unmodifiableList(groups);
    }

    /** Whether SQL did the grouping. */
    public boolean inDatabase() {
        return inDatabase;
    }

    /** Rows read. */
    public long scanned() {
        return scanned;
    }

    /** Rows read that the residual dropped before counting. */
    public long postFiltered() {
        return postFiltered;
    }

    /** Whether the read stopped at a ceiling, so the counts are a floor. */
    public boolean truncated() {
        return truncated;
    }

    /** Records counted across every group. */
    public long total() {
        return groups.stream().mapToLong(Group::count).sum();
    }

    /** The biggest group, the first one listed when several tie. */
    public Optional<Group> largest() {
        Group best = null;
        for (Group item : groups) {
            if (best == null || item.count() > best.count()) {
                best = item;
            }
        }
        return Optional.ofNullable(best);
    }

    /** One line for the terminal. */
    public String describe() {
        String where = inDatabase ? "in the archive" : "in memory";
        return groups.size() + " groups, " + total() + " records, counted " + where + (truncated ? ", truncated" : "");
    }

    void groups(List<Group> value) {
        groups = List.copyOf(value);
    }

    void scanned(long value) {
        scanned = value;
    }

    void scan() {
        scanned += 1;
    }

    void drop() {
        postFiltered += 1;
    }

    void truncated(boolean value) {
        truncated = value;
    }
}
