package io.github.rkaushikdev.trammelworks.clockfix;

import io.github.rkaushikdev.trammelworks.Json;

/** How a clock correction was arrived at. */
public enum Confidence implements Json.Valued {
    /** At a sync: the clock was right. */
    MEASURED("measured"),
    /** Between two syncs: the error was shared out along the sawtooth. */
    INTERPOLATED("interpolated"),
    /** Before the first or after the last sync: a projection. */
    EXTRAPOLATED("extrapolated"),
    /** No sync in the session: nothing is known. */
    UNKNOWN("unknown");

    private final String value;

    Confidence(String value) {
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
