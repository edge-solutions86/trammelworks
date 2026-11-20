package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.Doctor;
import io.github.rkaushikdev.trammelworks.DoctorReport;
import io.github.rkaushikdev.trammelworks.Finding;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code stanchion doctor}: check that this site will still be working next week. */
@Command(name = "doctor", description = "Check that this site will still be working next week.")
final class DoctorCommand implements Callable<Integer> {

    static final int EXIT_HEALTHY = 0;
    static final int EXIT_BROKEN = 1;
    static final int EXIT_WARNED = 2;

    @ParentCommand
    Cli root;

    @Option(names = "--strict", description = "Treat a warning as a failure.")
    boolean strict;

    @Option(names = "--json", description = "Write the machine readable form.")
    boolean asJson;

    @Option(names = "--quiet", description = "Print only failures and warnings.")
    boolean quiet;

    @Override
    public Integer call() {
        CliContext context = root.context();
        SiteConfig config = context.reporting(EXIT_BROKEN, context::load);
        DoctorReport report = Doctor.runChecks(config);
        if (asJson) {
            List<Map<String, Object>> findings = report.findings().stream()
                    .map(item -> {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("level", item.level().value());
                        out.put("check", item.check());
                        out.put("message", item.message());
                        return out;
                    })
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("site", config.site());
            payload.put("healthy", report.healthy());
            payload.put("findings", findings);
            context.echo(Json.dumps(payload, Json.Style.indented(1)));
        } else {
            printText(context, report, config.site());
        }
        return exitCode(report);
    }

    private void printText(CliContext context, DoctorReport report, String site) {
        context.echo("checking " + site);
        for (Finding item : report.findings()) {
            if (quiet && item.level() == Finding.Level.NOTE) {
                continue;
            }
            context.echo(item);
        }
        context.echo(report.failures().size() + " failures, "
                + report.warnings().size() + " warnings, " + report.findings().size() + " checks");
    }

    private int exitCode(DoctorReport report) {
        if (!report.failures().isEmpty()) {
            return EXIT_BROKEN;
        }
        if (!report.warnings().isEmpty()) {
            return strict ? EXIT_BROKEN : EXIT_WARNED;
        }
        return EXIT_HEALTHY;
    }
}
