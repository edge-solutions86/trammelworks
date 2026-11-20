package io.github.rkaushikdev.trammelworks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Everything the self check learned. */
public final class DoctorReport {

    private final List<Finding> findings = new ArrayList<>();

    /** An empty report. */
    public DoctorReport() {}

    /** Every finding, in the order the checks ran. */
    public List<Finding> findings() {
        return Collections.unmodifiableList(findings);
    }

    /** Record a finding. */
    public void add(Finding.Level level, String check, String message) {
        findings.add(new Finding(level, check, message));
    }

    /** Record something that stops the site working. */
    public void fail(String check, String message) {
        add(Finding.Level.FAIL, check, message);
    }

    /** Record something that will bite later. */
    public void warn(String check, String message) {
        add(Finding.Level.WARN, check, message);
    }

    /** Record something worth knowing. */
    public void note(String check, String message) {
        add(Finding.Level.NOTE, check, message);
    }

    /** The findings that stop the site working. */
    public List<Finding> failures() {
        return findings.stream()
                .filter(item -> item.level() == Finding.Level.FAIL)
                .toList();
    }

    /** The findings that will bite later. */
    public List<Finding> warnings() {
        return findings.stream()
                .filter(item -> item.level() == Finding.Level.WARN)
                .toList();
    }

    /** Whether nothing failed; warnings alone leave a site healthy. */
    public boolean healthy() {
        return failures().isEmpty();
    }

    /** Every finding, then the tally. */
    public String render() {
        List<String> lines = new ArrayList<>();
        findings.forEach(item -> lines.add(item.toString()));
        lines.add(failures().size() + " failures, " + warnings().size() + " warnings, " + findings.size() + " checks");
        return String.join("\n", lines);
    }
}
