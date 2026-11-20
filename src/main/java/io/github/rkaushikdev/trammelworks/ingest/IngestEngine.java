package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Clock;
import io.github.rkaushikdev.trammelworks.EventLog;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Layout;
import io.github.rkaushikdev.trammelworks.LogPathInfo;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.Registry;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every file on the box, followed together: discovery, one pipeline per followed file, and the
 * poll loop.
 *
 * <p>Containers come and go on a forecourt all day: the pump bridge restarts on every dispenser
 * reset, the loyalty sync is a cron job that exists for ninety seconds, and the price sign driver
 * only runs when head office pushed a price. So the set of files worth following is refreshed, not
 * worked out once at startup. A pipeline that is dropped is closed first, because whatever it was
 * holding is the last thing that container ever said.
 *
 * <p>Format detection happens once per file, on a sample of its first bytes, and the answer is
 * kept for the life of the pipeline. Re-detecting per poll would let a file change format halfway
 * through, which cannot happen and would only give the detector a chance to be wrong twice.
 */
public final class IngestEngine {

    private final SiteConfig config;
    private final CheckpointStore checkpoints;
    private final Registry metrics;
    private final Map<String, MultilineRule> rules;
    private final Clock clock;
    private final Map<String, UnitPipeline> pipelines = new LinkedHashMap<>();
    private final Map<String, Detection> detections = new LinkedHashMap<>();
    private EventLog events;

    private IngestEngine(Builder from) {
        this.config = from.config;
        this.checkpoints = from.checkpoints;
        this.events = from.events;
        this.metrics = from.metrics;
        this.rules = Map.copyOf(from.rules);
        this.clock = from.clock;
    }

    /** A builder for an engine following {@code config}'s log root. */
    public static Builder builder(SiteConfig config) {
        return new Builder(config);
    }

    /** Build an engine and open everything that is already there. */
    public static IngestEngine engineFor(SiteConfig config) {
        return builder(config).open();
    }

    /** The site config being followed. */
    public SiteConfig config() {
        return config;
    }

    /** The event log decisions go to. */
    public EventLog events() {
        return events;
    }

    /** Send events somewhere else from now on. */
    public void events(EventLog value) {
        events = value;
    }

    /** The counters and gauges this engine keeps. */
    public Registry metrics() {
        return metrics;
    }

    /** The open pipelines by path. */
    public Map<String, UnitPipeline> pipelines() {
        return Collections.unmodifiableMap(pipelines);
    }

    /** What each open file was detected as, by path. */
    public Map<String, Detection> detections() {
        return Collections.unmodifiableMap(detections);
    }

    /** The join limits every pipeline gets. */
    public JoinLimits limits() {
        return new JoinLimits(config.ingest().maxLineBytes());
    }

    /** Bring the pipeline set in line with what is on disk. */
    public Discovery refresh() {
        Discovery found = Discover.discover(
                Path.of(config.ingest().logRoot()), config.ingest().units());
        Map<String, LogPathInfo> wanted = new LinkedHashMap<>();
        for (LogPathInfo info : found.follow()) {
            wanted.put(info.path(), info);
        }
        for (String path : new ArrayList<>(pipelines.keySet())) {
            if (!wanted.containsKey(path)) {
                drop(path);
            }
        }
        for (Map.Entry<String, LogPathInfo> entry : wanted.entrySet()) {
            if (!pipelines.containsKey(entry.getKey())) {
                add(entry.getValue());
            }
        }
        metrics.gauge("ingest_files_followed").set(pipelines.size());
        found.reasons().forEach((reason, count) -> metrics.counter("ingest_skipped_" + reason.value())
                .inc(count));
        return found;
    }

    /** One pass over every followed file, in path order. */
    public List<LogRecord> poll() {
        List<LogRecord> out = new ArrayList<>();
        for (String path : sortedPaths()) {
            List<LogRecord> records = pipelines.get(path).poll();
            out.addAll(records);
            if (!records.isEmpty()) {
                metrics.incr("ingest_records", records.size());
            }
        }
        return out;
    }

    /** Shut everything down, emitting what each pipeline still holds. */
    public List<LogRecord> close() {
        List<LogRecord> out = new ArrayList<>();
        for (String path : sortedPaths()) {
            out.addAll(pipelines.get(path).close());
        }
        pipelines.clear();
        detections.clear();
        metrics.gauge("ingest_files_followed").set(0);
        return out;
    }

    private List<String> sortedPaths() {
        List<String> paths = new ArrayList<>(pipelines.keySet());
        paths.sort(Json::compareCodePoints);
        return paths;
    }

    private void add(LogPathInfo info) {
        Detection found;
        try {
            found = Detect.detect(info.path(), sample(Path.of(info.path())));
        } catch (ParseError exc) {
            metrics.incr("ingest_undetectable");
            events.exception("format_undetected", exc, "path", info.path());
            return;
        }
        if (found.disagreed()) {
            // The box was upgraded in place and nobody wrote it down.
            metrics.incr("ingest_layout_disagreement");
            events.warn(
                    "layout_disagreement",
                    "path",
                    info.path(),
                    "layout",
                    found.pathLayout().map(Layout::toString).orElse("None"),
                    "format",
                    found.format().value());
        }
        UnitPipeline pipeline = UnitPipeline.builder(config.site(), info, FragmentReader.forFormat(found.format()))
                .limits(limits())
                .stitcher(stitcher(info.unit()))
                .checkpoints(checkpoints)
                .clock(clock)
                .build();
        boolean resumed = pipeline.resume();
        pipelines.put(info.path(), pipeline);
        detections.put(info.path(), found);
        metrics.incr("ingest_files_opened");
        events.info(
                "file_opened",
                "path",
                info.path(),
                "unit",
                info.unit(),
                "format",
                found.format().value(),
                "resumed",
                resumed);
    }

    private void drop(String path) {
        UnitPipeline pipeline = pipelines.remove(path);
        detections.remove(path);
        List<LogRecord> left = pipeline.close();
        metrics.incr("ingest_files_closed");
        events.info("file_closed", "path", path, "flushed", left.size());
    }

    private Stitcher stitcher(String unit) {
        MultilineRule rule = rules.get(unit);
        return rule == null ? null : new Stitcher(rule);
    }

    private static byte[] sample(Path path) {
        try (InputStream handle = Files.newInputStream(path)) {
            return handle.readNBytes(Detect.SAMPLE_BYTES);
        } catch (IOException exc) {
            return new byte[0];
        }
    }

    /** An engine configured a collaborator at a time. */
    public static final class Builder {
        private final SiteConfig config;
        private CheckpointStore checkpoints;
        private EventLog events = EventLog.nullLog();
        private Registry metrics = new Registry();
        private Map<String, MultilineRule> rules = Map.of();
        private Clock clock = new SystemClock();

        private Builder(SiteConfig config) {
            this.config = config;
        }

        /** Where checkpoints go, so a restart resumes. */
        public Builder checkpoints(CheckpointStore value) {
            checkpoints = value;
            return this;
        }

        /** The event log. */
        public Builder events(EventLog value) {
            events = value;
            return this;
        }

        /** The metrics registry. */
        public Builder metrics(Registry value) {
            metrics = value;
            return this;
        }

        /** The per unit multiline rules. */
        public Builder rules(Map<String, MultilineRule> value) {
            rules = value;
            return this;
        }

        /** The clock stamping checkpoints. */
        public Builder clock(Clock value) {
            clock = value;
            return this;
        }

        /** The engine, with nothing opened yet. */
        public IngestEngine build() {
            return new IngestEngine(this);
        }

        /** The engine, with everything already on disk opened. */
        public IngestEngine open() {
            IngestEngine engine = build();
            engine.refresh();
            return engine;
        }
    }
}
