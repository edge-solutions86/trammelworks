package io.github.rkaushikdev.trammelworks.report;

import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.StanchionError;
import io.github.rkaushikdev.trammelworks.Text;
import java.util.Locale;

/** How a report is written. */
public enum ReportFormat {
    TEXT("text"),
    JSONL("jsonl"),
    CSV("csv"),
    MARKDOWN("markdown");

    private final String value;

    ReportFormat(String value) {
        this.value = value;
    }

    /** The name a command line gives the format. */
    public String value() {
        return value;
    }

    /** The format named {@code raw}, ignoring case and surrounding space. */
    public static ReportFormat parse(String raw) {
        String cleaned = Text.strip(raw).toLowerCase(Locale.ROOT);
        for (ReportFormat member : values()) {
            if (member.value.equals(cleaned)) {
                return member;
            }
        }
        throw new StanchionError("unknown report format " + Repr.of(raw));
    }

    @Override
    public String toString() {
        return value;
    }
}
