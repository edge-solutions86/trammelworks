package io.github.rkaushikdev.trammelworks.clockfix;

/**
 * A sync spotted in the record stream, before it is filed against a session.
 *
 * @param event the correction
 * @param unit the time daemon that logged it
 * @param exact whether the line measured the correction, rather than only announcing one
 */
public record Observation(SyncEvent event, String unit, boolean exact) {

    /** The session it belongs to. */
    public String bootId() {
        return event.bootId();
    }
}
