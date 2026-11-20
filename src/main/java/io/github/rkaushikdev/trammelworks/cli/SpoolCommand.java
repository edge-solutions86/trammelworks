package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.Units;
import io.github.rkaushikdev.trammelworks.spool.BudgetReport;
import io.github.rkaushikdev.trammelworks.spool.Eviction;
import io.github.rkaushikdev.trammelworks.spool.EvictionPlan;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import io.github.rkaushikdev.trammelworks.spool.SegmentInfo;
import io.github.rkaushikdev.trammelworks.spool.SegmentRead;
import io.github.rkaushikdev.trammelworks.spool.SegmentReader;
import io.github.rkaushikdev.trammelworks.spool.Segments;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import io.github.rkaushikdev.trammelworks.spool.Usage;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code stanchion spool}: report on the on disk spool. */
@Command(name = "spool", description = "Report on the on disk spool.")
final class SpoolCommand implements Callable<Integer> {

    @ParentCommand
    Cli root;

    @Option(names = "--segments", description = "List every segment.")
    boolean segments;

    @Option(names = "--plan", paramLabel = "SIZE", description = "Show what freeing this much would cost.")
    String planSize;

    @Option(names = "--verify", description = "Read every segment and check its frames.")
    boolean verify;

    @Override
    public Integer call() {
        CliContext context = root.context();
        SiteConfig config = context.loaded();
        SpoolSettings settings = config.spool();
        PriorityTable table = settings.table();
        List<SegmentInfo> found = Segments.listSegments(settings.path());
        Usage usage = Usage.measure(found);
        context.echo(BudgetReport.report(settings.budget(), usage));
        context.echo("usable " + Units.formatBytes(settings.budget().usableBytes()));
        for (String klass : table.shipOrder()) {
            String mark = table.protectedClasses().contains(klass) ? " protected" : "";
            context.echo("  " + Columns.left(klass, 12) + " "
                    + Columns.right(Units.formatBytes(usage.ofClass(klass)), 10) + mark);
        }
        context.echo("evictable " + Units.formatBytes(Eviction.evictableBytes(found, table)));
        if (segments) {
            listSegments(context, found);
        }
        if (verify) {
            verify(context, found);
        }
        if (planSize != null) {
            plan(context, found, table);
        }
        return Cli.EXIT_OK;
    }

    private static void listSegments(CliContext context, List<SegmentInfo> found) {
        for (SegmentInfo item : found) {
            context.echo("  " + Columns.right(item.sequence(), 6) + " " + Columns.left(item.klass(), 12) + " "
                    + Columns.right(Units.formatBytes(item.size()), 10) + " " + item.name());
        }
        if (found.isEmpty()) {
            context.echo("  no segments");
        }
    }

    private static void verify(CliContext context, List<SegmentInfo> found) {
        int bad = 0;
        int frames = 0;
        for (SegmentInfo item : found) {
            SegmentRead result = SegmentReader.readSegment(item.path());
            frames += result.frames().size();
            if (!result.whole()) {
                bad += 1;
                context.warn("  DAMAGED " + item.name() + ": " + result.note().orElse("None"));
            }
        }
        context.echo("verified " + found.size() + " segments, " + frames + " records, " + bad + " damaged");
    }

    private void plan(CliContext context, List<SegmentInfo> found, PriorityTable table) {
        long wanted = context.reporting(() -> Units.parseBytes(planSize));
        EvictionPlan plan = Eviction.planEviction(found, table, wanted);
        context.echo("to free " + Units.formatBytes(wanted) + ":");
        for (SegmentInfo item : plan.victims()) {
            context.echo("  delete " + item.name() + " (" + Units.formatBytes(item.size()) + ")");
        }
        if (plan.sufficient()) {
            context.echo("  frees " + Units.formatBytes(plan.freed()) + ", enough");
        } else {
            context.echo(
                    "  frees " + Units.formatBytes(plan.freed()) + ", short by " + Units.formatBytes(plan.shortfall()));
            context.echo("  " + Units.formatBytes(plan.protectedBytes()) + " is protected and cannot go");
        }
    }
}
