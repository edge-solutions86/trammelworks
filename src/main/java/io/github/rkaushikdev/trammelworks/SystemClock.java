package io.github.rkaushikdev.trammelworks;

import java.time.Instant;

/** The real clock, used everywhere outside tests. */
public final class SystemClock implements Clock {

    @Override
    public NanoTime now() {
        Instant now = Instant.now();
        return new NanoTime(now.getEpochSecond() * NanoTime.NS_PER_SECOND + now.getNano());
    }

    @Override
    public long monotonicNs() {
        return System.nanoTime();
    }
}
