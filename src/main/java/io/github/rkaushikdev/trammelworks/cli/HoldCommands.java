package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.SystemClock;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.HoldStore;
import io.github.rkaushikdev.trammelworks.archive.RetentionPolicy;
import io.github.rkaushikdev.trammelworks.archive.Row;
import io.github.rkaushikdev.trammelworks.archive.Schema;
import io.github.rkaushikdev.trammelworks.archive.SweepReport;
import io.github.rkaushikdev.trammelworks.archive.Sweeper;
import io.github.rkaushikdev.trammelworks.query.Parser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code stanchion hold} and {@code stanchion sweep}: legal holds and retention. */
final class HoldCommands {

    private HoldCommands() {}

    /**
     * The config and a connection, writable unless the caller only reads. Placing and lifting a
     * hold writes, so this cannot be the read only helper the query side uses; it defers to that one
     * on every path that only looks.
     */
    static CliContext.ReadArchive archiveOrFail(CliContext context, boolean readOnly) {
        if (readOnly) {
            return context.readingArchive("list holds for");
        }
        SiteConfig config = context.loaded();
        if (!config.archive().enabled()) {
            throw context.fail("this site keeps no archive");
        }
        ArchiveDb connection =
                context.reporting(() -> Schema.openArchive(config.archive().file(), false));
        return new CliContext.ReadArchive(config, connection);
    }

    /** Place, lift and list the legal holds on this site. */
    @Command(
            name = "hold",
            description = "Place, lift and list the legal holds on this site.",
            subcommands = {HoldList.class, HoldPlace.class, HoldLift.class})
    static final class Hold implements Callable<Integer> {

        @ParentCommand
        Cli root;

        @Override
        public Integer call() {
            new picocli.CommandLine(this).usage(root.context().printer());
            return Cli.EXIT_OK;
        }
    }

    /** Show every hold currently in force. */
    @Command(name = "list", description = "Show every hold currently in force.")
    static final class HoldList implements Callable<Integer> {

        @ParentCommand
        Hold group;

        @Override
        public Integer call() {
            CliContext context = group.root.context();
            CliContext.ReadArchive opened = context.reporting(() -> archiveOrFail(context, true));
            try (ArchiveDb connection = opened.connection()) {
                List<io.github.rkaushikdev.trammelworks.archive.Hold> holds = new HoldStore(connection).all();
                if (holds.isEmpty()) {
                    context.echo("no holds");
                    return Cli.EXIT_OK;
                }
                for (io.github.rkaushikdev.trammelworks.archive.Hold hold : holds) {
                    String window = hold.openEnded() ? "everything" : window(hold);
                    context.echo(Columns.left(hold.name(), 20) + " " + window + "  " + hold.reason());
                }
            }
            return Cli.EXIT_OK;
        }

        private static String window(io.github.rkaushikdev.trammelworks.archive.Hold hold) {
            String start = hold.coversFrom().map(NanoTime::isoformat).orElse("the beginning");
            String end = hold.coversUntil().map(NanoTime::isoformat).orElse("now");
            return start + " .. " + end;
        }
    }

    /** Stop retention deleting records, by name and reason. */
    @Command(name = "place", description = "Stop retention deleting records, by name and reason.")
    static final class HoldPlace implements Callable<Integer> {

        @ParentCommand
        Hold group;

        @Parameters(index = "0", paramLabel = "NAME")
        String name;

        @Parameters(index = "1", paramLabel = "REASON")
        String reason;

        @Option(names = "--from", description = "Start of the window.")
        String fromText;

        @Option(names = "--until", description = "End of the window.")
        String untilText;

        @Override
        public Integer call() {
            CliContext context = group.root.context();
            NanoTime now = new SystemClock().now();
            CliContext.ReadArchive opened = context.reporting(() -> archiveOrFail(context, false));
            try (ArchiveDb connection = opened.connection()) {
                io.github.rkaushikdev.trammelworks.archive.Hold hold =
                        context.reporting(() -> new io.github.rkaushikdev.trammelworks.archive.Hold(
                                name, reason, now, instant(fromText, now), instant(untilText, now)));
                new HoldStore(connection).place(hold);
            }
            context.echo("held " + name);
            return Cli.EXIT_OK;
        }

        private static Optional<NanoTime> instant(String text, NanoTime now) {
            return text == null ? Optional.empty() : Optional.of(new NanoTime(Parser.parseInstant(text, now)));
        }
    }

    /** Remove a hold, letting retention have those records again. */
    @Command(name = "lift", description = "Remove a hold, letting retention have those records again.")
    static final class HoldLift implements Callable<Integer> {

        @ParentCommand
        Hold group;

        @Parameters(index = "0", paramLabel = "NAME")
        String name;

        @Override
        public Integer call() {
            CliContext context = group.root.context();
            CliContext.ReadArchive opened = context.reporting(() -> archiveOrFail(context, false));
            boolean lifted;
            try (ArchiveDb connection = opened.connection()) {
                lifted = new HoldStore(connection).lift(name);
            }
            if (lifted) {
                context.echo("lifted " + name);
                return Cli.EXIT_OK;
            }
            throw context.fail("there is no hold called " + name);
        }
    }

    /** Apply the retention policy to the archive. */
    @Command(name = "sweep", description = "Apply the retention policy to the archive.")
    static final class Sweep implements Callable<Integer> {

        @ParentCommand
        Cli root;

        @Option(names = "--dry-run", description = "Say what would go without deleting it.")
        boolean dryRun;

        @Override
        public Integer call() {
            CliContext context = root.context();
            CliContext.ReadArchive opened = context.reporting(() -> archiveOrFail(context, false));
            SiteConfig config = opened.config();
            RetentionPolicy policy = config.archive().retention();
            try (ArchiveDb connection = opened.connection()) {
                context.echo(policy.describe());
                NanoTime now = new SystemClock().now();
                Sweeper sweeper =
                        new Sweeper(connection, policy, config.archive().maxDeletes());
                if (dryRun) {
                    List<Row> rows = sweeper.candidates(now, config.archive().maxDeletes());
                    context.echo(rows.size() + " records are old enough to be considered");
                    TreeSet<String> classes = new TreeSet<>();
                    rows.forEach(row -> classes.add(String.valueOf(row.get("klass"))));
                    for (String klass : classes) {
                        context.echo("  " + Columns.left(klass, 12) + " kept for "
                                + RetentionPolicy.formatRetention(policy.windowFor(klass)));
                    }
                    return Cli.EXIT_OK;
                }
                SweepReport report = sweeper.sweep(now);
                context.echo(report);
                Map<String, Long> byClass = new TreeMap<>(report.byClass());
                byClass.forEach((klass, count) -> context.echo("  " + Columns.left(klass, 12) + " " + count));
            }
            return Cli.EXIT_OK;
        }
    }
}
