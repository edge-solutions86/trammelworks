package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.PosixPath;
import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.clockfix.BootSession;
import io.github.rkaushikdev.trammelworks.clockfix.ClockStore;
import io.github.rkaushikdev.trammelworks.clockfix.DriftModel;
import io.github.rkaushikdev.trammelworks.clockfix.SessionLedger;
import io.github.rkaushikdev.trammelworks.clockfix.SyncEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code stanchion clock}: the boot sessions and clock corrections seen on this site. The ledger
 * lives beside the spool, not inside it, because an eviction sweep could delete it there, and losing
 * it turns every corrected record on the box back into a guess.
 */
@Command(name = "clock", description = "Show the boot sessions and the clock corrections seen on this site.")
final class ClockCommand implements Callable<Integer> {

    static final String STATE_DIRNAME = "state";

    private static final double NS_PER_SECOND = 1_000_000_000.0;
    private static final double NS_PER_MS = 1_000_000.0;

    @ParentCommand
    Cli root;

    @Option(names = "--all-boots", description = "Show every remembered boot, not just the last.")
    boolean allBoots;

    /** How fast the clock runs away, or why that is not known yet. */
    static String describeRate(DriftModel model) {
        OptionalDouble rate = model.ratePpm();
        if (rate.isEmpty()) {
            return "rate unknown, needs two syncs in one boot";
        }
        return "rate " + Repr.signedFixed(rate.getAsDouble(), 1) + " ppm";
    }

    /** One boot as lines of text. */
    static List<String> describeSession(BootSession session, int index) {
        List<String> lines = new ArrayList<>();
        lines.add("boot " + index + " " + session.bootId());
        lines.add("  up for " + Repr.fixed(session.durationNs() / NS_PER_SECOND, 0) + "s, "
                + session.syncs().size() + " syncs");
        for (SyncEvent event : session.syncs()) {
            String flag = event.large() ? " LARGE" : "";
            lines.add("  at " + Columns.right(Repr.fixed(event.monotonicNs() / NS_PER_SECOND, 1), 9) + "s  "
                    + Columns.left(event.source(), 10) + " "
                    + Columns.right(Repr.signedFixed(event.offsetNs() / NS_PER_MS, 1), 11) + " ms" + flag);
        }
        lines.add("  " + describeRate(new DriftModel(session)));
        return lines;
    }

    @Override
    public Integer call() {
        CliContext context = root.context();
        SiteConfig config = context.loaded();
        ClockStore store = new ClockStore(stateDirectory(config.spool().directory()));
        SessionLedger ledger = store.load();
        if (ledger.order().isEmpty()) {
            context.echo("no boot sessions recorded");
            return Cli.EXIT_OK;
        }
        context.echo("site " + config.site());
        context.echo(ledger.order().size() + " boots remembered, " + ledger.reboots() + " restarts");
        if (store.corrupt() != 0) {
            context.warn("the stored ledger was unreadable and has been treated as empty");
        }
        List<String> order = ledger.order();
        List<String> chosen = allBoots ? order : order.subList(order.size() - 1, order.size());
        for (int index = 0; index < chosen.size(); index++) {
            for (String line : describeSession(ledger.session(chosen.get(index)), index)) {
                context.echo(line);
            }
        }
        return Cli.EXIT_OK;
    }

    static Path stateDirectory(String spoolDirectory) {
        return Path.of(PosixPath.of(spoolDirectory).parent().toString()).resolve(STATE_DIRNAME);
    }
}
