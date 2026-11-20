package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.CollectReport;
import io.github.rkaushikdev.trammelworks.CycleReport;
import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.Site;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.Units;
import io.github.rkaushikdev.trammelworks.ship.ShipReport;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code stanchion run} and {@code stanchion ship}, the two ways the box is driven.
 *
 * <p>{@code run} is what the unit file starts: collect, ship, wait, repeat. It is bounded by {@code
 * --cycles} rather than looping forever by default, because the same command is how an engineer runs
 * one pass by hand and watches. {@code ship} does the uplink half on its own, for the visit where a
 * modem has been swapped and the engineer wants to watch the backlog drain. Neither sleeps between
 * cycles unless asked to.
 */
final class RunCommands {

    /** How many passes {@code run} makes by default. */
    static final int DEFAULT_CYCLES = 1;

    private RunCommands() {}

    /** Everything several cycles did. */
    static final class RunTotals {
        int cycles;
        long read;
        long spooled;
        long redacted;
        long sent;
        long bytesSent;
        String stopped = "";

        void absorb(CycleReport report) {
            cycles += 1;
            read += report.collect().read();
            spooled += report.collect().shippedToSpool();
            redacted += report.collect().redacted();
            sent += report.ship().recordsSent();
            bytesSent += report.ship().bytesSent();
            if (!report.ship().stopped().isEmpty()) {
                stopped = report.ship().stopped();
            }
        }

        @Override
        public String toString() {
            return cycles + " cycles, read " + read + ", spooled " + spooled + ", redacted " + redacted + ", sent "
                    + sent + " in " + bytesSent + " bytes";
        }
    }

    /** Collect from the container logs and ship, once or repeatedly. */
    @Command(name = "run", description = "Collect from the container logs and ship, once or repeatedly.")
    static final class Run implements Callable<Integer> {

        @ParentCommand
        Cli root;

        @Option(
                names = "--cycles",
                defaultValue = "1",
                showDefaultValue = picocli.CommandLine.Help.Visibility.ALWAYS,
                description = "How many passes.")
        int cycles;

        @Option(names = "--forever", description = "Keep going until interrupted.")
        boolean forever;

        @Option(names = "--interval", description = "Seconds to wait between passes.")
        Double interval;

        @Override
        public Integer call() {
            CliContext context = root.context();
            if (cycles < 1) {
                throw context.fail("cycles must be at least 1");
            }
            SiteConfig config = context.reporting(context::load);
            Site site = context.reporting(() -> {
                Site built = Site.build(config);
                built.open();
                return built;
            });
            double wait = interval != null
                    ? interval
                    : Units.totalSeconds(config.ship().interval());
            RunTotals totals = new RunTotals();
            try {
                drive(context, site, totals, wait);
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                context.warn("interrupted");
            } finally {
                CollectReport left = site.close();
                if (left.read() != 0 && context.verbose()) {
                    context.warn("flushed " + left.read() + " records on shutdown");
                }
                site.release();
            }
            context.echo(totals);
            return Cli.EXIT_OK;
        }

        private void drive(CliContext context, Site site, RunTotals totals, double wait) throws InterruptedException {
            int done = 0;
            while (forever || done < cycles) {
                CycleReport report = site.cycle();
                totals.absorb(report);
                done += 1;
                if (context.verbose()) {
                    context.warn("cycle " + done + ": read " + report.collect().read() + ", sent "
                            + report.ship().recordsSent());
                }
                if (report.ship().waitSeconds() != 0) {
                    context.warn("link is unhappy, waiting "
                            + Repr.fixed(report.ship().waitSeconds(), 1) + "s");
                }
                if ((forever || done < cycles) && wait > 0) {
                    Thread.sleep((long) (Math.max(wait, report.ship().waitSeconds()) * 1000));
                }
            }
        }
    }

    /** Drain the spool to the collector without collecting anything new. */
    @Command(name = "ship", description = "Drain the spool to the collector without collecting anything new.")
    static final class Ship implements Callable<Integer> {

        @ParentCommand
        Cli root;

        @Option(
                names = "--passes",
                defaultValue = "1",
                showDefaultValue = picocli.CommandLine.Help.Visibility.ALWAYS,
                description = "How many uplink passes.")
        int passes;

        @Override
        public Integer call() {
            CliContext context = root.context();
            if (passes < 1) {
                throw context.fail("passes must be at least 1");
            }
            Site site = context.reporting(() -> {
                Site built = Site.build(context.load());
                built.open();
                return built;
            });
            try {
                long records = 0;
                long bytes = 0;
                for (int pass = 0; pass < passes; pass++) {
                    ShipReport report = site.ship();
                    records += report.recordsSent();
                    bytes += report.bytesSent();
                    if (!report.blockedByMeter().isEmpty()) {
                        context.warn("held back by the meter: "
                                + report.blockedByMeter().size() + " segments");
                    }
                    if (!report.stopped().isEmpty()) {
                        context.warn("stopped: " + report.stopped());
                        break;
                    }
                    if (report.idle()) {
                        break;
                    }
                }
                context.echo("sent " + records + " records in " + bytes + " bytes");
                context.echo(site.shipper().meter().describe());
            } finally {
                site.release();
            }
            return Cli.EXIT_OK;
        }
    }
}
