package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Json;

/** What happened to a followed file between two polls. */
public enum TailEvent implements Json.Valued {
    /** Nothing. */
    NONE("none"),
    /** It grew. */
    GROWN("grown"),
    /** It was rotated by rename: a new file now sits at the path. */
    ROTATED("rotated"),
    /** It was rotated in place, or rewritten from the start. */
    TRUNCATED("truncated"),
    /** It went away. */
    MISSING("missing");

    private final String value;

    TailEvent(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
