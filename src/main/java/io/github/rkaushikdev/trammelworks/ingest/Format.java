package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Json;

/** What a reader has to be pointed at. */
public enum Format implements Json.Valued {
    /** The CRI text format. */
    CRI("cri"),
    /** Docker json-file. */
    JSONFILE("jsonfile"),
    /** journald export. */
    JOURNAL("journal");

    private final String value;

    Format(String value) {
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
