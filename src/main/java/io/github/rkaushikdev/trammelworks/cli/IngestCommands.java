package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.LogPathInfo;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.SystemClock;
import io.github.rkaushikdev.trammelworks.ingest.Detection;
import io.github.rkaushikdev.trammelworks.ingest.Discovery;
import io.github.rkaushikdev.trammelworks.ingest.IngestEngine;
import io.github.rkaushikdev.trammelworks.ingest.SkipReason;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code stanchion discover} and {@code stanchion tail}: what the readers see. */
final class IngestCommands {

    static final int MAX_MESSAGE_PREVIEW = 160;

    private IngestCommands() {}

    /** One line per record, the way a person reads it. */
    static String render(LogRecord record, boolean full) {
        String body = full ? record.message() : preview(record.message(), MAX_MESSAGE_PREVIEW);
        return record.observedAt().isoformat() + " " + record.unit() + " "
                + record.stream().value() + " " + body.replace("\n", "\\n");
    }

    /** The first {@code limit} characters. */
    static String preview(String text, int limit) {
        if (text.codePointCount(0, text.length()) <= limit) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, limit));
    }

    /** List the container log files this site would follow. */
    @Command(name = "discover", description = "List the container log files this site would follow.")
    static final class Discover implements Callable<Integer> {

        @ParentCommand
        Cli root;

        @Option(
                names = "--skipped",
                negatable = true,
                defaultValue = "true",
                fallbackValue = "true",
                description = "Also list what was left alone.")
        boolean skipped;

        @Override
        public Integer call() {
            CliContext context = root.context();
            SiteConfig config = context.reporting(context::load);
            IngestEngine engine =
                    IngestEngine.builder(config).clock(new SystemClock()).build();
            Discovery found = context.reporting(engine::refresh);
            context.echo("log root " + config.ingest().logRoot());
            for (LogPathInfo info : found.follow()) {
                Detection detection = engine.detections().get(info.path());
                String shape = detection != null ? detection.format().value() : "unknown";
                context.echo(
                        "follow  " + Columns.left(info.unit(), 20) + " " + Columns.left(shape, 9) + " " + info.path());
            }
            if (found.follow().isEmpty()) {
                context.echo("follow  nothing");
            }
            engine.close();
            if (!skipped) {
                return Cli.EXIT_OK;
            }
            Map<String, Integer> reasons = new TreeMap<>();
            for (Map.Entry<SkipReason, Integer> entry : found.reasons().entrySet()) {
                reasons.put(entry.getKey().value(), entry.getValue());
            }
            reasons.forEach((reason, count) -> context.echo("skipped " + Columns.left(reason, 20) + " " + count));
            return Cli.EXIT_OK;
        }
    }

    /** Run the ingest pipeline for a few passes and print what comes out. */
    @Command(name = "tail", description = "Run the ingest pipeline for a few passes and print what comes out.")
    static final class Tail implements Callable<Integer> {

        @ParentCommand
        Cli root;

        @Option(
                names = "--passes",
                defaultValue = "1",
                showDefaultValue = Visibility.ALWAYS,
                description = "How many polls to run.")
        int passes;

        boolean full;

        @Option(names = "--full", description = "Print whole messages.")
        void full(boolean value) {
            full = true;
        }

        @Option(names = "--truncate", description = "Cut long messages short.")
        void truncate(boolean value) {
            full = false;
        }

        @Option(names = "--unit", description = "Only these units.")
        List<String> units = new ArrayList<>();

        @Override
        public Integer call() {
            CliContext context = root.context();
            if (passes < 1) {
                throw context.fail("passes must be at least 1");
            }
            Set<String> wanted = new HashSet<>(units);
            int[] total = {0};
            IngestEngine engine = context.reporting(() -> {
                IngestEngine built = IngestEngine.builder(context.load())
                        .clock(new SystemClock())
                        .build();
                built.refresh();
                for (int pass = 0; pass < passes; pass++) {
                    print(context, built.poll(), wanted, total);
                }
                print(context, built.close(), wanted, total);
                return built;
            });
            if (context.verbose()) {
                context.warn(total[0] + " records from " + engine.detections().size() + " files");
            }
            return Cli.EXIT_OK;
        }

        private void print(CliContext context, List<LogRecord> records, Set<String> wanted, int[] total) {
            for (LogRecord record : records) {
                if (!wanted.isEmpty() && !wanted.contains(record.unit())) {
                    continue;
                }
                total[0] += 1;
                context.echo(render(record, full));
            }
        }
    }
}
