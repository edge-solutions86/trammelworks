package io.github.rkaushikdev.trammelworks;

import java.util.Map;
import java.util.Optional;

/**
 * What a read of a small state file found, and whether the file was usable.
 *
 * @param payload the mapping, when the file was usable
 * @param missing whether there was no file at all
 * @param corrupt whether there was a file that could not be used
 */
public record ReadResult(Optional<Map<String, Object>> payload, boolean missing, boolean corrupt) {

    static ReadResult absent() {
        return new ReadResult(Optional.empty(), true, false);
    }

    static ReadResult broken() {
        return new ReadResult(Optional.empty(), false, true);
    }

    static ReadResult of(Map<String, Object> payload) {
        return new ReadResult(Optional.of(payload), false, false);
    }

    /** Whether there is a payload to use. */
    public boolean usable() {
        return payload.isPresent();
    }
}
