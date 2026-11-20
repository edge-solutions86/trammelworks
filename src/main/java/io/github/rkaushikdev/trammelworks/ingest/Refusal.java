package io.github.rkaushikdev.trammelworks.ingest;

/** Why a path under the log root will not be read. */
public enum Refusal {
    /** It resolves to somewhere outside the root. */
    OUTSIDE("outside the log root"),
    /** It resolves to a directory, fifo, device or socket. */
    NOT_A_FILE("not a regular file"),
    /** It cannot be resolved at all. */
    UNREADABLE("cannot be resolved"),
    /** Its links go round in a circle. */
    LOOP("symlink loop");

    private final String value;

    Refusal(String value) {
        this.value = value;
    }

    /** The reason as an operator reads it. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
