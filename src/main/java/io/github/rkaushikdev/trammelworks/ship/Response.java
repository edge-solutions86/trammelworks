package io.github.rkaushikdev.trammelworks.ship;

import java.util.OptionalDouble;

/**
 * What one send produced.
 *
 * @param outcome what to do about it
 * @param status the HTTP status, or 0 when there was none
 * @param retryAfter the collector's hint, in seconds, when it gave one
 * @param message why, when it failed
 * @param bytesSent what the send cost on the meter
 */
public record Response(Outcome outcome, int status, OptionalDouble retryAfter, String message, long bytesSent) {

    /** An answer with a status and nothing else. */
    public Response(Outcome outcome, int status) {
        this(outcome, status, OptionalDouble.empty(), "", 0);
    }

    /** An answer with a status and a message. */
    public Response(Outcome outcome, int status, String message) {
        this(outcome, status, OptionalDouble.empty(), message, 0);
    }

    /** A throttle with its hint. */
    public static Response throttled(int status, double retryAfter) {
        return new Response(Outcome.THROTTLED, status, OptionalDouble.of(retryAfter), "", 0);
    }

    /** Whether the batch was accepted. */
    public boolean ok() {
        return outcome == Outcome.OK;
    }

    /** The same answer, costing {@code bytes}. */
    public Response withBytesSent(long bytes) {
        return new Response(outcome, status, retryAfter, message, bytes);
    }
}
