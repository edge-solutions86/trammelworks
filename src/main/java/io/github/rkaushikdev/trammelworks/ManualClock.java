package io.github.rkaushikdev.trammelworks;

/**
 * A clock a test drives by hand.
 *
 * <p>The two readings move independently on purpose. A site controller that gets an NTP correction
 * jumps its wall clock while the monotonic counter carries on, and the drift code has to be
 * exercised against exactly that.
 */
public final class ManualClock implements Clock {

    private NanoTime wall;
    private long monotonic;

    /** A clock reading the epoch on both counters. */
    public ManualClock() {
        this(new NanoTime(0), 0);
    }

    /** A clock starting at the given readings. */
    public ManualClock(NanoTime wall, long monotonic) {
        this.wall = wall;
        this.monotonic = monotonic;
    }

    @Override
    public NanoTime now() {
        return wall;
    }

    @Override
    public long monotonicNs() {
        return monotonic;
    }

    /** Move both readings forward by the same amount, the ordinary case. */
    public void advance(double seconds) {
        if (seconds < 0) {
            throw new StanchionError("manual clock cannot advance backwards");
        }
        long delta = (long) (seconds * NanoTime.NS_PER_SECOND);
        wall = wall.shifted(delta);
        monotonic += delta;
    }

    /** Jump the wall clock only, the way an NTP correction does. */
    public void stepWall(double seconds) {
        wall = wall.shifted((long) (seconds * NanoTime.NS_PER_SECOND));
    }

    /** Set the wall clock to an instant. */
    public void setWall(NanoTime value) {
        wall = value;
    }

    /** Set the wall clock to an RFC3339 timestamp. */
    public void setWall(String value) {
        wall = NanoTime.parse(value);
    }

    /** Set the monotonic counter, as a fresh boot does. */
    public void setMonotonic(long value) {
        monotonic = value;
    }
}
