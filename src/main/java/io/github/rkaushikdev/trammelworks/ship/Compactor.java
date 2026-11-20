package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.EventLog;
import io.github.rkaushikdev.trammelworks.Registry;
import io.github.rkaushikdev.trammelworks.SpoolError;
import io.github.rkaushikdev.trammelworks.spool.SegmentInfo;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Rewrites part shipped segments, and keeps the acknowledgement ledger in step. */
public final class Compactor {

    private final Path directory;
    private final AckLedger ledger;
    private final long minReclaim;
    private final double minFraction;
    private final EventLog events;
    private final Registry metrics;

    /** A compactor with the default thresholds. */
    public Compactor(Path directory, AckLedger ledger) {
        this(
                directory,
                ledger,
                Compaction.DEFAULT_MIN_RECLAIM,
                Compaction.DEFAULT_MIN_FRACTION,
                EventLog.nullLog(),
                new Registry());
    }

    /** A compactor with everything given. */
    public Compactor(
            Path directory, AckLedger ledger, long minReclaim, double minFraction, EventLog events, Registry metrics) {
        this.directory = directory;
        this.ledger = ledger;
        this.minReclaim = minReclaim;
        this.minFraction = minFraction;
        this.events = events;
        this.metrics = metrics;
    }

    /** Where the segments live. */
    public Path directory() {
        return directory;
    }

    /** The counters this compactor writes. */
    public Registry metrics() {
        return metrics;
    }

    /** Compact everything worth it, nothing pinned. */
    public CompactionResult run(List<SegmentInfo> segments) {
        return run(segments, Set.of());
    }

    /** Compact everything worth compacting, in order of what it gives back. */
    public CompactionResult run(List<SegmentInfo> segments, Set<Path> pinned) {
        Survey survey = Compaction.planCompaction(segments, ledger, minReclaim, minFraction, pinned);
        int done = 0;
        long dropped = 0;
        long freed = 0;
        List<String> failed = new ArrayList<>(survey.unreadable());
        for (String name : survey.unreadable()) {
            metrics.incr("spool_compaction_failures");
            events.warn("compaction_skipped", "segment", name, "reason", "will not read");
        }
        for (CompactionPlan plan : survey.plans()) {
            long size;
            try {
                size = Compaction.rewrite(plan.segment().path(), plan.drop());
            } catch (SpoolError | UncheckedIOException exc) {
                failed.add(plan.name());
                metrics.incr("spool_compaction_failures");
                events.warn(
                        "compaction_failed", "segment", plan.name(), "reason", Objects.toString(exc.getMessage(), ""));
                continue;
            }
            ledger.resize(plan.name(), plan.keep());
            long gained = plan.segment().size() - size;
            done++;
            dropped += plan.drop();
            freed += gained;
            metrics.incr("spool_compacted_segments");
            metrics.incr("spool_compacted_bytes", gained);
            events.info(
                    "segment_compacted",
                    "segment",
                    plan.name(),
                    "dropped",
                    plan.drop(),
                    "kept",
                    plan.keep(),
                    "freed",
                    gained);
        }
        return new CompactionResult(done, dropped, freed, failed);
    }
}
