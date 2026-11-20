package io.github.rkaushikdev.trammelworks.redact;

import java.util.List;

/**
 * A one line description of the redaction pass, for the doctor command.
 *
 * @param enabled whether the pass runs
 * @param keepFirst leading PAN digits kept
 * @param keepLast trailing PAN digits kept
 * @param rules the site rules' names
 */
public record RedactSummary(boolean enabled, int keepFirst, int keepLast, List<String> rules) {

    /** Copies the names. */
    public RedactSummary {
        rules = List.copyOf(rules);
    }

    @Override
    public String toString() {
        if (!enabled) {
            return "redaction DISABLED";
        }
        String shape = "keep " + keepFirst + "+" + keepLast;
        if (rules.isEmpty()) {
            return "redaction on, " + shape + ", card rules only";
        }
        return "redaction on, " + shape + ", plus " + rules.size() + " site rules";
    }
}
