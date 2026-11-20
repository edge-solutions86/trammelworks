package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.Deploy;
import io.github.rkaushikdev.trammelworks.Deployment;
import io.github.rkaushikdev.trammelworks.HousekeepReport;
import io.github.rkaushikdev.trammelworks.Housekeeper;
import io.github.rkaushikdev.trammelworks.Site;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.SystemdUnit;
import io.github.rkaushikdev.trammelworks.archive.Footprint;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code stanchion units} and {@code stanchion housekeep}. */
final class UnitsCommands {

    private UnitsCommands() {}

    /** Generate the systemd units for this site. */
    @Command(name = "units", description = "Generate the systemd units for this site.")
    static final class Units implements Callable<Integer> {

        @ParentCommand
        Cli root;

        @Option(names = "--config-path", description = "The config path to name inside the units.")
        String configPath;

        @Option(names = "--executable", description = "Where stanchion is installed.")
        String executable;

        @Option(names = "--user", description = "The account the units run as.")
        String user;

        @Option(names = "--serve", description = "Include the read only query service.")
        boolean serve;

        @Option(names = "--at", description = "When housekeeping runs, HH:MM.")
        String housekeepAt;

        @Option(
                names = "--to",
                paramLabel = "DIRECTORY",
                description = "Write the units into this directory instead of printing them.")
        Path target;

        @Override
        public Integer call() {
            CliContext context = root.context();
            SiteConfig config = context.loaded();
            String chosenConfig = configPath;
            if (chosenConfig == null) {
                // The unit names the path on the box it will run on, which is where the config
                // lives after commissioning and not necessarily where it is now. An engineer
                // generating these from a working copy gets the installed path and a line saying so.
                String here = context.configPath().toString();
                if (here.startsWith("/")) {
                    chosenConfig = here;
                } else {
                    chosenConfig = Deploy.DEFAULT_CONFIG;
                    context.warn("units will name " + Deploy.DEFAULT_CONFIG + ", not " + here
                            + ", pass --config-path to change it");
                }
            }
            String path = chosenConfig;
            List<SystemdUnit> built = context.reporting(() -> {
                Deployment deployment = new Deployment().withConfigPath(path).withServe(serve);
                if (executable != null) {
                    deployment = deployment.withExecutable(executable);
                }
                if (user != null) {
                    deployment = deployment.withAccount(user, user);
                }
                if (housekeepAt != null) {
                    deployment = deployment.withHousekeepAt(housekeepAt);
                }
                return Deploy.unitsFor(config, deployment);
            });
            if (target == null) {
                for (SystemdUnit unit : built) {
                    context.echo("# " + unit.path());
                    context.write(unit.body());
                }
                return Cli.EXIT_OK;
            }
            List<Path> written = Deploy.writeUnits(built, target);
            written.forEach(context::echo);
            context.echo(written.size() + " units written, none installed");
            return Cli.EXIT_OK;
        }
    }

    /** Apply retention, the size cap and the vacuum, in that order. */
    @Command(name = "housekeep", description = "Apply retention, the size cap and the vacuum, in that order.")
    static final class Housekeep implements Callable<Integer> {

        @ParentCommand
        Cli root;

        @Option(names = "--dry-run", description = "Report the footprint and change nothing.")
        boolean dryRun;

        @Override
        public Integer call() {
            CliContext context = root.context();
            SiteConfig config = context.loaded();
            if (!config.archive().enabled()) {
                throw context.fail("this site keeps no archive, so there is nothing to housekeep");
            }
            Site site = context.reporting(() -> {
                Site opened = Site.build(config);
                opened.open();
                return opened;
            });
            try {
                Housekeeper keeper = site.housekeeper().orElseThrow(() -> context.fail("the archive is not open"));
                if (dryRun) {
                    Footprint found = keeper.footprint();
                    context.echo(
                            "archive " + io.github.rkaushikdev.trammelworks.Units.formatBytes(found.total()) + " of "
                                    + io.github.rkaushikdev.trammelworks.Units.formatBytes(
                                            config.archive().maxBytes()));
                    context.echo(io.github.rkaushikdev.trammelworks.Units.formatBytes(found.reclaimable())
                            + " would come back from a vacuum");
                    return Cli.EXIT_OK;
                }
                HousekeepReport report = context.reporting(keeper::run);
                context.echo(report);
                context.echo(io.github.rkaushikdev.trammelworks.Units.formatBytes(
                                report.before().total()) + " before, "
                        + io.github.rkaushikdev.trammelworks.Units.formatBytes(
                                report.after().total()) + " after");
                return Cli.EXIT_OK;
            } finally {
                site.close();
                site.release();
            }
        }
    }
}
