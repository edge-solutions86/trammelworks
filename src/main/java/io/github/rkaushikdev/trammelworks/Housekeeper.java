package io.github.rkaushikdev.trammelworks;

import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.archive.Footprint;
import io.github.rkaushikdev.trammelworks.archive.Space;
import io.github.rkaushikdev.trammelworks.archive.SweepReport;
import io.github.rkaushikdev.trammelworks.archive.Sweeper;
import io.github.rkaushikdev.trammelworks.archive.TrimReport;
import io.github.rkaushikdev.trammelworks.archive.Trimmer;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import java.nio.file.Path;

/**
 * Everything the archive needs doing to it that is not writing a record.
 *
 * <p>The order is the whole design. <b>Sweep</b> first: retention decides what may go, and anything
 * it takes is free. <b>Trim</b> second, only if the cap says there is no room: it deletes records
 * somebody would have kept, so trimming first would take records the sweep was about to remove
 * anyway, at the cost of something that mattered. <b>Vacuum</b> last, because it is the only step
 * that hands space back to the card, and doing it before the deletions would rewrite the whole file
 * and then punch holes in it.
 *
 * <p>Every step reports rather than raises. A site whose card is failing must still collect.
 */
public final class Housekeeper {

    private final ArchiveDb connection;
    private final ArchiveSettings settings;
    private final Sweeper sweeper;
    private final PriorityTable table;
    private final Clock clock;
    private final EventLog events;
    private final Registry metrics;

    /** A housekeeper with every collaborator given. */
    public Housekeeper(
            ArchiveDb connection,
            ArchiveSettings settings,
            Sweeper sweeper,
            PriorityTable table,
            Clock clock,
            EventLog events,
            Registry metrics) {
        this.connection = connection;
        this.settings = settings;
        this.sweeper = sweeper;
        this.table = table;
        this.clock = clock;
        this.events = events;
        this.metrics = metrics;
    }

    /** A housekeeper with the default table, the system clock and no events. */
    public Housekeeper(ArchiveDb connection, ArchiveSettings settings, Sweeper sweeper) {
        this(connection, settings, sweeper, new PriorityTable(), new SystemClock(), EventLog.nullLog(), new Registry());
    }

    /** The archive file. */
    public Path path() {
        return settings.file();
    }

    /** The archive's size now. */
    public Footprint footprint() {
        return Space.measure(connection, path());
    }

    /** Retention, then the cap, then give the card its space back. */
    public HousekeepReport run() {
        HousekeepReport report = new HousekeepReport();
        report.before(footprint());
        report.sweep(sweep());
        report.trim(trim());
        if (settings.vacuum()) {
            report.reclaimed(vacuum());
        }
        report.after(footprint());
        return report;
    }

    /** Apply the retention policy. */
    public SweepReport sweep() {
        SweepReport report = sweeper.sweep(clock.now());
        if (report.deleted() != 0) {
            metrics.incr("archive_deleted", report.deleted());
            events.info("archive_swept", "deleted", report.deleted(), "held", report.held());
        }
        return report;
    }

    /** Take records retention kept, when the cap leaves no choice. */
    public TrimReport trim() {
        if (!settings.trimToCap()) {
            return new TrimReport();
        }
        Trimmer trimmer = new Trimmer(connection, path(), settings.maxBytes(), table, Space.DEFAULT_TRIM_BATCH);
        TrimReport report = trimmer.run(sweeper.holds().all(), clock.now());
        if (report.deleted() != 0) {
            metrics.incr("archive_trimmed", report.deleted());
            events.warn(
                    "archive_trimmed", "deleted", report.deleted(), "held", report.held(), "stopped", report.stopped());
        }
        return report;
    }

    /** Rewrite the archive if the free space is worth the write cycles. Returns the bytes freed. */
    public long vacuum() {
        if (!Space.worthVacuuming(footprint(), settings.vacuumMinReclaimBytes(), Space.DEFAULT_MIN_FRACTION)) {
            return 0;
        }
        long freed;
        try {
            freed = Space.vacuum(connection, path());
        } catch (RetentionError exc) {
            metrics.incr("archive_vacuum_failed");
            events.exception("archive_vacuum_failed", exc);
            return 0;
        }
        metrics.incr("archive_vacuumed");
        metrics.incr("archive_vacuum_freed", freed);
        events.info("archive_vacuumed", "freed", freed);
        return freed;
    }
}
