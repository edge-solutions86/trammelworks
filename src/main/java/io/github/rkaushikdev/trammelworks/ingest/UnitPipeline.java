package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Clock;
import io.github.rkaushikdev.trammelworks.Fragment;
import io.github.rkaushikdev.trammelworks.Identity;
import io.github.rkaushikdev.trammelworks.LogPathInfo;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.SystemClock;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One container log file followed end to end: tail it, read fragments, join the chunks the
 * runtime split, then stitch what the application meant as one event. Stitching before joining
 * would look at half a line and decide it starts an event.
 *
 * <p>When the file is replaced, everything held belongs to the old file and nothing more is coming
 * for it, so that (and shutdown) is when held lines are flushed.
 */
public final class UnitPipeline {

    static final Set<TailEvent> FLUSHING_EVENTS = Set.of(TailEvent.ROTATED, TailEvent.TRUNCATED, TailEvent.MISSING);

    private final String site;
    private final LogPathInfo info;
    private final Stitcher stitcher;
    private final CheckpointStore checkpoints;
    private final Clock clock;
    private final Tailer tailer;
    private final Joiner joiner;
    private final PipelineStats stats = new PipelineStats();

    private UnitPipeline(Builder from) {
        this.site = from.site;
        this.info = from.info;
        this.stitcher = from.stitcher;
        this.checkpoints = from.checkpoints;
        this.clock = from.clock;
        this.tailer = new Tailer(Path.of(info.path()), from.reader);
        this.joiner = new Joiner(site, unit(), from.limits);
    }

    /** A builder for the pipeline following {@code info} on {@code site}. */
    public static Builder builder(String site, LogPathInfo info, FragmentReader reader) {
        return new Builder(site, info, reader);
    }

    /** The followed file. */
    public LogPathInfo info() {
        return info;
    }

    /** The per file numbers. */
    public PipelineStats stats() {
        return stats;
    }

    /** The tailer underneath. */
    public Tailer tailer() {
        return tailer;
    }

    /** The unit whose file this is. */
    public String unit() {
        return Identity.parseUnit(info.unit());
    }

    /** The checkpoint key. One per followed file, not one per unit. */
    public String key() {
        return site + "_" + unit() + "_" + info.attempt().orElse(0);
    }

    /** Seek to the stored offset if it still describes this file. */
    public boolean resume() {
        if (checkpoints == null) {
            return false;
        }
        Optional<Checkpoint> stored = checkpoints.load(key());
        if (stored.isEmpty() || !stored.get().path().equals(info.path())) {
            return false;
        }
        FileIdentity.Stat found;
        try {
            found = FileIdentity.stat(tailer.path());
        } catch (IOException exc) {
            return false;
        }
        if (!stored.get().matches(found.identity())) {
            return false;
        }
        if (stored.get().offset() > found.size()) {
            return false;
        }
        tailer.seekTo(stored.get().offset());
        return true;
    }

    /** One pass. Returns whatever finished during it. */
    public List<LogRecord> poll() {
        PollResult result = tailer.poll();
        List<LogRecord> out = new ArrayList<>();
        if (FLUSHING_EVENTS.contains(result.event())) {
            out.addAll(flush());
            stats.event(result.event(), result.lostBytes());
        }
        stats.poll(result.fragments().size(), result.skipped());
        for (Fragment fragment : result.fragments()) {
            joiner.feed(fragment).ifPresent(joined -> out.addAll(stitch(joined)));
        }
        stats.produced(out.size());
        checkpoint();
        return out;
    }

    /** Shutdown. Everything held is emitted, marked for what it is. */
    public List<LogRecord> close() {
        List<LogRecord> out = flush();
        stats.produced(out.size());
        checkpoint();
        return out;
    }

    private List<LogRecord> flush() {
        List<LogRecord> out = new ArrayList<>();
        for (LogRecord record : joiner.flush()) {
            out.addAll(stitch(record));
        }
        if (stitcher != null) {
            out.addAll(stitcher.flush());
        }
        return out;
    }

    private List<LogRecord> stitch(LogRecord record) {
        return stitcher == null ? List.of(record) : stitcher.feed(record);
    }

    /**
     * Store the position that has been parsed, not the one read. Bytes that do not form a
     * complete line yet are held back, and checkpointing past them would skip them next start.
     */
    private void checkpoint() {
        if (checkpoints == null || tailer.identity().isEmpty()) {
            return;
        }
        FileIdentity identity = tailer.identity().get();
        checkpoints.save(new Checkpoint(
                key(),
                info.path(),
                identity.device(),
                identity.inode(),
                tailer.offset() - tailer.buffered(),
                clock.now()));
    }

    /** A pipeline configured a setting at a time. */
    public static final class Builder {
        private final String site;
        private final LogPathInfo info;
        private final FragmentReader reader;
        private JoinLimits limits = new JoinLimits();
        private Stitcher stitcher;
        private CheckpointStore checkpoints;
        private Clock clock = new SystemClock();

        private Builder(String site, LogPathInfo info, FragmentReader reader) {
            this.site = site;
            this.info = info;
            this.reader = reader;
        }

        /** The join limits. */
        public Builder limits(JoinLimits value) {
            limits = value;
            return this;
        }

        /** The stitcher, when the unit has a multiline rule. */
        public Builder stitcher(Stitcher value) {
            stitcher = value;
            return this;
        }

        /** Where checkpoints go, when they are kept. */
        public Builder checkpoints(CheckpointStore value) {
            checkpoints = value;
            return this;
        }

        /** The clock stamping checkpoints. */
        public Builder clock(Clock value) {
            clock = value;
            return this;
        }

        /** The pipeline. */
        public UnitPipeline build() {
            return new UnitPipeline(this);
        }
    }
}
