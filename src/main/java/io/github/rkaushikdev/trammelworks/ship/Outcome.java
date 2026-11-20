package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.Json;

/** What came back from an upload attempt, sorted by what to do about it. */
public enum Outcome implements Json.Valued {
    /** Accepted. */
    OK("ok"),
    /** A timeout, a 5xx, a reset: wait and try again. */
    RETRYABLE("retryable"),
    /** Refused for good: retrying wastes metered bytes. */
    TERMINAL("terminal"),
    /** Told to slow down, with a hint of how long. */
    THROTTLED("throttled");

    private final String value;

    Outcome(String value) {
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
