package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Json;

/** Why a path in the log root is not being followed. */
public enum SkipReason implements Json.Valued {
    /** Not a container log path. */
    UNPARSEABLE("unparseable"),
    /** A link out of the root, or something that is not a file. */
    OUTSIDE("outside_the_root"),
    /** A rotated copy. */
    ROTATED("rotated"),
    /** A compressed rotation. */
    COMPRESSED("compressed"),
    /** Another layout's view of a container already followed. */
    DUPLICATE("duplicate"),
    /** A unit the config's filter leaves out. */
    NOT_SELECTED("not_selected"),
    /** Empty. */
    EMPTY("empty");

    private final String value;

    SkipReason(String value) {
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
