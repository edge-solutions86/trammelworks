package io.github.rkaushikdev.trammelworks.service;

import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.StanchionError;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.archive.Footprint;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.archive.Space;
import io.github.rkaushikdev.trammelworks.ship.AckLedger;
import io.github.rkaushikdev.trammelworks.ship.AckStore;
import io.github.rkaushikdev.trammelworks.ship.Meter;
import io.github.rkaushikdev.trammelworks.ship.MeterStore;
import io.github.rkaushikdev.trammelworks.spool.Budget;
import io.github.rkaushikdev.trammelworks.spool.SegmentInfo;
import io.github.rkaushikdev.trammelworks.spool.Segments;
import io.github.rkaushikdev.trammelworks.spool.Usage;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The site's numbers, gathered from disk rather than from a running process.
 *
 * <p>The service is a separate process from the collector and cannot read its counters, and a
 * channel between them would let the query service stall the thing that ships records. So
 * everything here is derived from what is on disk: segments in the spool directory, rows in the
 * archive, watermarks in the state files. That makes these gauges rather than counters.
 *
 * <p>The text form is deliberately plain, one {@code name value} pair per line: the estate poller
 * that reads it runs on a box older than this software and parses with awk.
 */
public final class Metrics {

    /** The content type the text form is served as. */
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private Metrics() {}

    /** What the spool directory says about itself. */
    public static void spoolGauges(SiteConfig config, Gauges into) {
        Budget budget = config.spool().budget();
        List<SegmentInfo> segments = Segments.listSegments(config.spool().path());
        Usage usage = Usage.measure(segments);
        into.set("spool_limit_bytes", budget.limitBytes());
        into.set("spool_usable_bytes", budget.usableBytes());
        into.set("spool_used_bytes", usage.used());
        into.set("spool_free_bytes", Math.max(budget.limitBytes() - usage.used(), 0));
        into.set("spool_segments", usage.segments());
        into.set("spool_full_percent", (long) (budget.fillRatio(usage) * 100));
        for (String klass : config.spool().table().shipOrder()) {
            into.set("spool_class_bytes_" + klass, usage.ofClass(klass));
            into.set(
                    "spool_class_segments_" + klass,
                    segments.stream().filter(item -> item.klass().equals(klass)).count());
        }
    }

    /** What the meter and the watermarks say. */
    public static void shipGauges(SiteConfig config, Path state, Gauges into) {
        Meter meter = new MeterStore(state).load(config.ship());
        into.set("ship_allowance_bytes", meter.allowanceBytes());
        into.set("ship_used_bytes", meter.usedBytes());
        into.set("ship_remaining_bytes", meter.remaining());
        into.set("ship_allowance_percent", (long) (meter.spentRatio() * 100));
        AckLedger ledger = new AckStore(state).load();
        into.set("ship_outstanding_records", ledger.outstanding());
        into.set("ship_pending_segments", ledger.pendingSegments().size());
    }

    /** What the archive file says, when there is one. */
    public static void archiveGauges(SiteConfig config, Gauges into) {
        ArchiveSettings settings = config.archive();
        if (!settings.enabled()) {
            into.set("archive_enabled", 0);
            return;
        }
        into.set("archive_enabled", 1);
        if (!Files.exists(settings.file())) {
            into.noteMissing("archive");
            return;
        }
        Footprint footprint;
        try (ArchiveDb connection = Schema.openArchive(settings.file(), true)) {
            into.set("archive_records", new Repository(connection).count());
            footprint = Space.measure(connection, settings.file());
        } catch (StanchionError exc) {
            into.noteMissing("archive");
            return;
        }
        into.set("archive_limit_bytes", settings.maxBytes());
        into.set("archive_bytes", footprint.total());
        into.set("archive_used_bytes", footprint.used());
        into.set("archive_reclaimable_bytes", footprint.reclaimable());
    }

    /**
     * Every gauge this site can answer for right now. Each area is read on its own and a failure in
     * one does not stop the others: the point of this endpoint is the moment something is wrong,
     * which is exactly when one of these reads is most likely to fail.
     */
    public static Gauges gather(SiteConfig config, Path state) {
        Gauges found = new Gauges();
        try {
            spoolGauges(config, found);
        } catch (StanchionError | UncheckedIOException exc) {
            found.noteMissing("spool");
        }
        try {
            shipGauges(config, state, found);
        } catch (StanchionError | UncheckedIOException exc) {
            found.noteMissing("ship");
        }
        try {
            archiveGauges(config, found);
        } catch (StanchionError | UncheckedIOException exc) {
            found.noteMissing("archive");
        }
        found.set("site_areas_unreadable", found.missing().size());
        return found;
    }
}
