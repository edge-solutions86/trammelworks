package io.github.rkaushikdev.trammelworks;

/** A number that reflects the present, such as spool bytes in use. */
public final class Gauge {

    private final String name;
    private long value;

    /** A gauge at zero. */
    public Gauge(String name) {
        this.name = name;
    }

    /** The series name. */
    public String name() {
        return name;
    }

    /** The current reading. */
    public long value() {
        return value;
    }

    /** Replace the reading. */
    public void set(long reading) {
        value = reading;
    }

    /** Move the reading by a signed amount. A wrong delta may take it negative, and that shows. */
    public void add(long delta) {
        value += delta;
    }
}
