package io.github.rkaushikdev.trammelworks.query;

/**
 * Everything at or before an instant.
 *
 * @param atNs the instant, in nanoseconds since the epoch
 * @param written the text it was read from, or empty
 */
public record Until(long atNs, String written) implements Node {

    /** An instant with no written form. */
    public Until(long atNs) {
        this(atNs, "");
    }

    @Override
    public String render() {
        return "until " + (written.isEmpty() ? Long.toString(atNs) : written);
    }
}
