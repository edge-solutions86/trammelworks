package io.github.rkaushikdev.trammelworks.archive;

import io.github.rkaushikdev.trammelworks.NanoTime;
import java.util.List;
import java.util.Optional;

/**
 * The legal holds recorded in the archive. They live in the archive rather than the config file
 * because a hold is placed in the middle of the night, has to take effect without a restart, and
 * has to be visible to anyone who later opens the database and asks why a year of records is
 * still there.
 */
public final class HoldStore {

    private final ArchiveDb db;

    /** The holds of one archive. */
    public HoldStore(ArchiveDb db) {
        this.db = db;
    }

    /** Record a hold, replacing one of the same name. */
    public void place(Hold hold) {
        db.transaction(() -> db.execute(
                "INSERT OR REPLACE INTO holds (name, reason, placed_ns, from_ns, until_ns) VALUES (?, ?, ?, ?, ?)",
                hold.name(),
                hold.reason(),
                hold.placedAt().epochNs(),
                hold.coversFrom().map(NanoTime::epochNs).orElse(null),
                hold.coversUntil().map(NanoTime::epochNs).orElse(null)));
    }

    /** Remove a hold. Returns whether there was one. */
    public boolean lift(String name) {
        return db.transaction(() -> db.execute("DELETE FROM holds WHERE name = ?", name)) > 0;
    }

    /** Every hold, oldest first. */
    public List<Hold> all() {
        return db.query("SELECT * FROM holds ORDER BY placed_ns, name").stream()
                .map(row -> new Hold(
                        row.getString("name"),
                        row.getString("reason"),
                        new NanoTime(row.getLong("placed_ns")),
                        row.isNull("from_ns") ? Optional.empty() : Optional.of(new NanoTime(row.getLong("from_ns"))),
                        row.isNull("until_ns") ? Optional.empty() : Optional.of(new NanoTime(row.getLong("until_ns")))))
                .toList();
    }

    /** A hold by name. */
    public Optional<Hold> get(String name) {
        return all().stream().filter(hold -> hold.name().equals(name)).findFirst();
    }
}
