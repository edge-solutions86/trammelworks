package io.github.rkaushikdev.trammelworks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The counters and gauges one process reports.
 *
 * <p>There is no scrape endpoint on a forecourt box. The numbers are a paragraph at the end of the
 * nightly summary, and the point is that they add up: fragments read minus fragments joined should
 * equal records produced. Names are checked because the summary is parsed by the estate and a typo
 * silently creates a second series that nobody totals.
 */
public final class Registry {

    /** Metric names are lower snake case. */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$");

    private final Map<String, Counter> counters = new LinkedHashMap<>();
    private final Map<String, Gauge> gauges = new LinkedHashMap<>();

    /** Refuse a malformed metric name. */
    public static String checkName(String name) {
        if (!NAME_PATTERN.matcher(name).find()) {
            throw new StanchionError("malformed metric name " + Repr.of(name));
        }
        return name;
    }

    /** The counter of that name, created at zero the first time. */
    public Counter counter(String name) {
        checkName(name);
        if (gauges.containsKey(name)) {
            throw new StanchionError(name + " is already a gauge");
        }
        return counters.computeIfAbsent(name, Counter::new);
    }

    /** The gauge of that name, created at zero the first time. */
    public Gauge gauge(String name) {
        checkName(name);
        if (counters.containsKey(name)) {
            throw new StanchionError(name + " is already a counter");
        }
        return gauges.computeIfAbsent(name, Gauge::new);
    }

    /** Add one to a counter. */
    public void incr(String name) {
        incr(name, 1);
    }

    /** Add an amount to a counter. */
    public void incr(String name, long amount) {
        counter(name).inc(amount);
    }

    /** Every series and its value, sorted by name, as a fresh mutable map. */
    public Map<String, Long> snapshot() {
        Map<String, Long> merged = new TreeMap<>();
        counters.forEach((name, counter) -> merged.put(name, counter.value()));
        gauges.forEach((name, gauge) -> merged.put(name, gauge.value()));
        return new LinkedHashMap<>(merged);
    }

    /** One {@code name value} pair per line, sorted, for the nightly summary. */
    public String render() {
        List<String> lines = new ArrayList<>();
        snapshot().forEach((name, value) -> lines.add(name + " " + value));
        return String.join("\n", lines);
    }

    /** Forget every series. */
    public void reset() {
        counters.clear();
        gauges.clear();
    }
}
