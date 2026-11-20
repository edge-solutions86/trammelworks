package io.github.rkaushikdev.trammelworks.query;

import io.github.rkaushikdev.trammelworks.NanoTime;

/**
 * One line of a grouped count.
 *
 * @param key the group's value, or a bucket's start in nanoseconds
 * @param count how many records fell in it
 * @param firstNs the earliest of them
 * @param lastNs the latest of them
 */
public record Group(String key, long count, long firstNs, long lastNs) {

    /** A group with no span. */
    public Group(String key, long count) {
        this(key, count, 0, 0);
    }

    /** The earliest record's instant. */
    public NanoTime firstAt() {
        return new NanoTime(firstNs);
    }

    /** The latest record's instant. */
    public NanoTime lastAt() {
        return new NanoTime(lastNs);
    }

    /** How long the group covers, never negative. */
    public long spanNs() {
        return Math.max(lastNs - firstNs, 0);
    }

    @Override
    public String toString() {
        return key + " " + count;
    }
}
