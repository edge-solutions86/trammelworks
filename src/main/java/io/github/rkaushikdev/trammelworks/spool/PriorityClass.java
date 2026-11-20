package io.github.rkaushikdev.trammelworks.spool;

import io.github.rkaushikdev.trammelworks.ConfigError;

/**
 * One priority class, and everything it decides: ship order, eviction order and retention floor.
 *
 * @param name the class name, as segment names carry it
 * @param weight higher ships first and is evicted last
 * @param isProtected whether the class is never evicted at all
 */
public record PriorityClass(String name, int weight, boolean isProtected) {

    /** Refuses a malformed name or a negative weight. */
    public PriorityClass {
        Segments.checkClass(name);
        if (weight < 0) {
            throw new ConfigError("a class weight cannot be negative", name);
        }
    }

    /** An unprotected class. */
    public PriorityClass(String name, int weight) {
        this(name, weight, false);
    }
}
