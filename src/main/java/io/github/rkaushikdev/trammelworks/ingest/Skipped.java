package io.github.rkaushikdev.trammelworks.ingest;

/**
 * A path discovery leaves alone, and why.
 *
 * @param path the path
 * @param reason why it is not followed
 */
public record Skipped(String path, SkipReason reason) {}
