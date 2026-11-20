package io.github.rkaushikdev.trammelworks.query;

/**
 * Everything at or after an instant.
 *
 * @param atNs the instant, in nanoseconds since the epoch
 * @param written the text it was read from, or empty
 */
public record Since(long atNs, String written) implements Node {

    /** An instant with no written form. */
    public Since(long atNs) {
        this(atNs, "");
    }

    @Override
    public String render() {
        return "since " + (written.isEmpty() ? Long.toString(atNs) : written);
    }
}
