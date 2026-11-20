package io.github.rkaushikdev.trammelworks.service;

import io.github.rkaushikdev.trammelworks.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Everything one look at a site's disk can tell you. */
public final class Gauges {

    private final Map<String, Long> values = new LinkedHashMap<>();
    private final List<String> missing = new ArrayList<>();

    /** Nothing read yet. */
    public Gauges() {}

    /** Set one gauge. */
    public void set(String name, long value) {
        values.put(name, value);
    }

    /**
     * Record that an area could not be read, without failing the whole read. A site whose archive is
     * being vacuumed cannot answer for it, and a metrics endpoint that returns nothing at that moment
     * looks like the site is down.
     */
    public void noteMissing(String area) {
        missing.add(area);
    }

    /** The gauges, in the order they were set. */
    public Map<String, Long> values() {
        return Collections.unmodifiableMap(values);
    }

    /** The areas that could not be read. */
    public List<String> missing() {
        return Collections.unmodifiableList(missing);
    }

    /** One {@code name value} pair per line, sorted, with a final newline when there are any. */
    public String render() {
        StringBuilder out = new StringBuilder();
        asDict().forEach((name, value) ->
                out.append(name).append(' ').append(value).append('\n'));
        return out.toString();
    }

    /** The gauges, sorted by name. */
    public Map<String, Long> asDict() {
        Map<String, Long> sorted = new TreeMap<>(Json::compareCodePoints);
        sorted.putAll(values);
        return new LinkedHashMap<>(sorted);
    }
}
