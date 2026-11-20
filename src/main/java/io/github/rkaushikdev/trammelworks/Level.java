package io.github.rkaushikdev.trammelworks;

import java.util.Locale;

/**
 * How loud an event is. Coarse on purpose: on a box nobody logs into, the only question that
 * matters is whether a human has to drive out to the site.
 */
public enum Level {
    /** Detail for whoever is debugging on site. */
    DEBUG(10),
    /** Ordinary progress. */
    INFO(20),
    /** Something to look at in the morning. */
    WARN(30),
    /** Something to drive out for. */
    ERROR(40);

    private final int severity;

    Level(int severity) {
        this.severity = severity;
    }

    /** The numeric severity. */
    public int severity() {
        return severity;
    }

    /** Read a level by name, forgiving case and padding. */
    public static Level parse(String raw) {
        String cleaned = Text.strip(raw).toUpperCase(Locale.ROOT);
        for (Level level : values()) {
            if (level.name().equals(cleaned)) {
                return level;
            }
        }
        throw new StanchionError("unknown level " + Repr.of(raw));
    }
}
