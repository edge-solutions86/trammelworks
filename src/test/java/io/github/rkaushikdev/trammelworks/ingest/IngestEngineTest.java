package io.github.rkaushikdev.trammelworks.ingest;

import static io.github.rkaushikdev.trammelworks.LogFiles.criFull;
import static io.github.rkaushikdev.trammelworks.LogFiles.criLine;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.EventLog;
import io.github.rkaushikdev.trammelworks.IngestConfig;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Level;
import io.github.rkaushikdev.trammelworks.LogPathInfo;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.ManualClock;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.PosixPath;
import io.github.rkaushikdev.trammelworks.Registry;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.SiteTree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestEngineTest {

    static final String SITE = "US-TX-0417";
    static final String TS = "2025-03-04T11:30:00.123456789Z";
    static final String CID = "3f".repeat(32);

    @TempDir
    Path tmp;

    SiteTree tree;

    @BeforeEach
    void setUp() {
        tree = new SiteTree(tmp.resolve("log"));
    }

    SiteConfig configFor(List<String> units) {
        return SiteConfig.builder(SITE)
                .ingest(IngestConfig.builder()
                        .logRoot(PosixPath.asPosix(tree.root()))
                        .units(units)
                        .build())
                .build();
    }

    IngestEngine build() {
        return build(UnaryOperator.identity());
    }

    IngestEngine build(UnaryOperator<IngestEngine.Builder> over) {
        return build(configFor(List.of()), over);
    }

    IngestEngine build(SiteConfig config, UnaryOperator<IngestEngine.Builder> over) {
        return over.apply(IngestEngine.builder(config).clock(new ManualClock())).build();
    }

    static List<String> messages(List<LogRecord> records) {
        return records.stream().map(LogRecord::message).toList();
    }

    static EventLog debugLog() {
        return new EventLog(Level.DEBUG, new ManualClock());
    }

    @Test
    void refreshOpensWhatIsOnDisk() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "up")));
        IngestEngine engine = build();
        Discovery found = engine.refresh();
        assertThat(found.follow()).extracting(LogPathInfo::unit).containsExactly("pump-bridge");
        assertThat(engine.pipelines()).hasSize(1);
    }

    @Test
    void pollReturnsTheRecords() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "nozzle 1 lifted")));
        IngestEngine engine = build();
        engine.refresh();
        assertThat(messages(engine.poll())).containsExactly("nozzle 1 lifted");
    }

    @Test
    void recordsCarryTheSiteAndUnit() {
        tree.writeCri("tank-gauge", List.of(criFull(TS, "level 41200")));
        IngestEngine engine = build();
        engine.refresh();
        LogRecord record = engine.poll().get(0);
        assertThat(record.site()).isEqualTo(SITE);
        assertThat(record.unit()).isEqualTo("tank-gauge");
    }

    @Test
    void twoUnitsAreBothFollowed() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one")));
        tree.writeCri("tank-gauge", List.of(criFull(TS, "two")));
        IngestEngine engine = build();
        engine.refresh();
        assertThat(messages(engine.poll())).containsExactlyInAnyOrder("one", "two");
    }

    @Test
    void aSecondPollReadsOnlyWhatIsNew() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one")));
        IngestEngine engine = build();
        engine.refresh();
        engine.poll();
        tree.writeCri("pump-bridge", List.of(criFull(TS, "two")));
        assertThat(messages(engine.poll())).containsExactly("two");
    }

    @Test
    void refreshPicksUpAContainerThatStartedLater() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one")));
        IngestEngine engine = build();
        engine.refresh();
        engine.poll();
        tree.writeCri("loyalty-sync", List.of(criFull(TS, "sync start")));
        engine.refresh();
        assertThat(messages(engine.poll())).containsExactly("sync start");
    }

    @Test
    void aFileThatGoesAwayIsClosed() throws IOException {
        Path path = tree.writeCri("loyalty-sync", List.of(criFull(TS, "one")));
        IngestEngine engine = build();
        engine.refresh();
        engine.poll();
        Files.delete(path);
        engine.refresh();
        assertThat(engine.pipelines()).isEmpty();
    }

    @Test
    void closingADroppedFileFlushesWhatItHeld() throws IOException {
        Path path = tree.writeCri("loyalty-sync", List.of(criLine(TS, "stdout", "P", "half a line")));
        IngestEngine engine = build();
        engine.refresh();
        engine.poll();
        Files.delete(path);
        EventLog events = debugLog();
        engine.events(events);
        engine.refresh();
        List<Map<String, Object>> closed = events.records().stream()
                .filter(record -> "file_closed".equals(record.get("event")))
                .toList();
        assertThat(closed).isNotEmpty();
        assertThat(closed.get(0).get("flushed")).isEqualTo(1L);
    }

    @Test
    void closeEmitsEverythingHeld() {
        tree.writeCri("pump-bridge", List.of(criLine(TS, "stdout", "P", "unfinished")));
        IngestEngine engine = build();
        engine.refresh();
        engine.poll();
        assertThat(messages(engine.close())).containsExactly("unfinished");
        assertThat(engine.pipelines()).isEmpty();
    }

    @Test
    void theUnitFilterNarrowsWhatIsFollowed() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one")));
        tree.writeCri("tank-gauge", List.of(criFull(TS, "two")));
        IngestEngine engine = build(configFor(List.of("pump-bridge")), UnaryOperator.identity());
        engine.refresh();
        assertThat(messages(engine.poll())).containsExactly("one");
    }

    @Test
    void jsonFileRead() {
        Path path = tree.dockerPath(CID);
        String payload = Json.dumps(Maps.of("log", "auth approved\n", "stream", "stdout", "time", TS));
        tree.write(path, payload + "\n");
        IngestEngine engine = build();
        engine.refresh();
        assertThat(messages(engine.poll())).containsExactly("auth approved");
    }

    @Test
    void layoutClash() {
        Path path = tree.dockerPath(CID);
        tree.write(path, criFull(TS, "cri content in a docker tree"));
        EventLog events = debugLog();
        IngestEngine engine = build(builder -> builder.events(events));
        engine.refresh();
        assertThat(events.records())
                .anySatisfy(record -> assertThat(record.get("event")).isEqualTo("layout_disagreement"));
        assertThat(engine.metrics().snapshot()).containsEntry("ingest_layout_disagreement", 1L);
    }

    @Test
    void theMetricsCountTheFilesAndRecords() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one") + criFull(TS, "two")));
        Registry metrics = new Registry();
        IngestEngine engine = build(builder -> builder.metrics(metrics));
        engine.refresh();
        engine.poll();
        Map<String, Long> snapshot = metrics.snapshot();
        assertThat(snapshot).containsEntry("ingest_files_followed", 1L);
        assertThat(snapshot).containsEntry("ingest_files_opened", 1L);
        assertThat(snapshot).containsEntry("ingest_records", 2L);
    }

    @Test
    void theFollowedGaugeFallsToZeroOnClose() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one")));
        Registry metrics = new Registry();
        IngestEngine engine = build(builder -> builder.metrics(metrics));
        engine.refresh();
        engine.close();
        assertThat(metrics.snapshot()).containsEntry("ingest_files_followed", 0L);
    }

    @Test
    void skipsAreCountedByReason() throws IOException {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one")));
        Files.createDirectories(tree.root());
        Files.writeString(tree.root().resolve("messages"), "not ours", StandardCharsets.UTF_8);
        Registry metrics = new Registry();
        IngestEngine engine = build(builder -> builder.metrics(metrics));
        engine.refresh();
        assertThat(metrics.snapshot()).containsEntry("ingest_skipped_unparseable", 1L);
    }

    @Test
    void aStitcherIsAppliedWhenARuleNamesTheUnit() {
        tree.writeCri(
                "payment-kernel", List.of(criFull(TS, "boom"), criFull(TS, "    at Pump.reset"), criFull(TS, "next")));
        Map<String, MultilineRule> rules =
                Map.of("payment-kernel", MultilineRule.continuing("payment-kernel", "^\\s+"));
        IngestEngine engine = build(builder -> builder.rules(rules));
        engine.refresh();
        assertThat(messages(engine.poll())).containsExactly("boom\n    at Pump.reset");
    }

    @Test
    void noRuleMeansNoStitching() {
        tree.writeCri("payment-kernel", List.of(criFull(TS, "boom"), criFull(TS, "    at Pump.reset")));
        IngestEngine engine = build();
        engine.refresh();
        assertThat(engine.poll()).hasSize(2);
    }

    @Test
    void checkpointsLetARestartResume() {
        CheckpointStore store = new CheckpointStore(tmp.resolve("ckpt"));
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one")));
        IngestEngine first = build(builder -> builder.checkpoints(store));
        first.refresh();
        first.poll();
        tree.writeCri("pump-bridge", List.of(criFull(TS, "two")));
        IngestEngine second = build(builder -> builder.checkpoints(store));
        second.refresh();
        assertThat(messages(second.poll())).containsExactly("two");
    }

    @Test
    void engineForOpensImmediately() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one")));
        IngestEngine engine = IngestEngine.builder(configFor(List.of()))
                .clock(new ManualClock())
                .open();
        assertThat(engine.pipelines()).hasSize(1);
    }

    @Test
    void anEmptyLogRootFollowsNothing() {
        IngestEngine engine = build();
        engine.refresh();
        assertThat(engine.pipelines()).isEmpty();
        assertThat(engine.poll()).isEmpty();
    }

    @Test
    void theDefaultBuilderOpensWithTheSystemClock() {
        tree.writeCri("pump-bridge", List.of(criFull(TS, "one")));
        IngestEngine engine = IngestEngine.engineFor(configFor(List.of()));
        assertThat(engine.detections()).hasSize(1);
        assertThat(engine.limits().maxLineBytes()).isEqualTo(IngestConfig.DEFAULT_MAX_LINE_BYTES);
        assertThat(engine.config().site()).isEqualTo(SITE);
    }
}
