package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.CollectReport;
import io.github.rkaushikdev.trammelworks.LogPathInfo;
import io.github.rkaushikdev.trammelworks.Site;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.Units;
import io.github.rkaushikdev.trammelworks.ingest.Backfiller;
import io.github.rkaushikdev.trammelworks.ingest.Discover;
import io.github.rkaushikdev.trammelworks.ingest.Discovery;
import io.github.rkaushikdev.trammelworks.ingest.JoinLimits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code stanchion backfill}: read the rotated log files into the spool and the archive. */
@Command(name = "backfill", description = "Read the rotated log files into the spool and the archive.")
final class BackfillCommand implements Callable<Integer> {

    @ParentCommand
    Cli root;

    @Option(names = "--unit", description = "Only these units.")
    List<String> units = new ArrayList<>();

    @Option(names = "--dry-run", description = "List what would be read, read nothing.")
    boolean dryRun;

    @Option(names = "--max-bytes", description = "Refuse a rotated file larger than this.")
    String maxBytes;

    @Override
    public Integer call() {
        CliContext context = root.context();
        SiteConfig config = context.reporting(context::load);
        long cap =
                context.reporting(() -> maxBytes == null ? Backfiller.DEFAULT_MAX_BYTES : Units.parseBytes(maxBytes));
        Discovery found = context.reporting(() -> Discover.discover(
                Path.of(config.ingest().logRoot()), config.ingest().units()));
        List<LogPathInfo> wanted = found.follow().stream()
                .filter(info -> units.isEmpty() || units.contains(info.unit()))
                .toList();
        if (wanted.isEmpty()) {
            context.echo("no followed files, so nothing to backfill");
            return Cli.EXIT_OK;
        }
        if (dryRun) {
            describe(context, wanted, cap);
            return Cli.EXIT_OK;
        }
        Site site = Site.build(config);
        site.open();
        long total = 0;
        try {
            for (LogPathInfo info : wanted) {
                Backfiller reader = new Backfiller(config.site(), info, new JoinLimits(), cap);
                CollectReport report = site.absorb(reader.records());
                total += report.shippedToSpool();
                context.echo(Columns.left(info.unit(), 20) + " " + reader.report());
            }
        } finally {
            site.close();
            site.release();
        }
        context.echo(total + " records backfilled");
        return Cli.EXIT_OK;
    }

    private static void describe(CliContext context, List<LogPathInfo> wanted, long cap) {
        long total = 0;
        for (LogPathInfo info : wanted) {
            List<LogPathInfo> sources = new Backfiller("US-XX-0000", info, new JoinLimits(), cap).sources();
            long held = sources.stream()
                    .mapToLong(item -> size(Path.of(item.path())))
                    .sum();
            total += held;
            context.echo(Columns.left(info.unit(), 20) + " " + sources.size() + " rotated files, "
                    + Units.formatBytes(held));
        }
        context.echo(Units.formatBytes(total) + " would be read");
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exc) {
            return 0;
        }
    }
}
