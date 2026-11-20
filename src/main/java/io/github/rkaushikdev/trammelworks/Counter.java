package io.github.rkaushikdev.trammelworks;

/** A number that only goes up, reset only by a restart. */
public final class Counter {

    private final String name;
    private long value;

    /** A counter at zero. */
    public Counter(String name) {
        this.name = name;
    }

    /** The series name. */
    public String name() {
        return name;
    }

    /** The current count. */
    public long value() {
        return value;
    }

    /** Add one. */
    public void inc() {
        inc(1);
    }

    /** Add a non-negative amount. */
    public void inc(long amount) {
        if (amount < 0) {
            throw new StanchionError("counter " + name + " cannot decrease");
        }
        value += amount;
    }
}
