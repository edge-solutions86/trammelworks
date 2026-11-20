package io.github.rkaushikdev.trammelworks.archive;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Units;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * How long each class stays on the box, already checked against the scheme floors.
 *
 * <p>Three things decide whether a record may go, in an order where each overrules the ones after
 * it: a legal hold; the class floor (payment records have a 180 day dispute window and a site may
 * not keep them for less); and the configured window. A rule a site can shorten is not a floor, so
 * the floors are enforced here and the sweeper only ever asks this what it may delete.
 *
 * @param windows how long each class is kept
 */
public record RetentionPolicy(Map<String, Duration> windows) {

    /** Card scheme dispute windows. A site may keep longer, never shorter. */
    public static final Map<String, Duration> CLASS_FLOORS =
            ordered("payment", Duration.ofDays(180), "standard", Duration.ofDays(7), "chatter", Duration.ZERO);

    /** What a site keeps when its config says nothing. */
    public static final Map<String, Duration> DEFAULT_WINDOWS =
            ordered("payment", Duration.ofDays(180), "standard", Duration.ofDays(30), "chatter", Duration.ofDays(7));

    private static Map<String, Duration> ordered(Object... pairs) {
        Map<String, Duration> out = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            out.put((String) pairs[index], (Duration) pairs[index + 1]);
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** Refuses a negative window or one below its class floor. */
    public RetentionPolicy {
        windows = Maps.frozen(windows);
        for (Map.Entry<String, Duration> entry : windows.entrySet()) {
            if (entry.getValue().isNegative()) {
                throw new ConfigError("a retention window cannot be negative", entry.getKey());
            }
            Duration floor = floorFor(entry.getKey());
            if (entry.getValue().compareTo(floor) < 0) {
                throw new ConfigError(
                        "a retention window is below the scheme floor",
                        entry.getKey() + ": " + formatRetention(entry.getValue()) + " < " + formatRetention(floor));
            }
        }
    }

    /** The default windows. */
    public RetentionPolicy() {
        this(DEFAULT_WINDOWS);
    }

    /** A class's floor, zero for a class the schemes say nothing about. */
    public static Duration floorFor(String klass) {
        return CLASS_FLOORS.getOrDefault(klass, Duration.ZERO);
    }

    /**
     * A window in days, which is how card schemes, the estate and the auditors state one; "25w5d"
     * is not how anybody says a dispute window.
     */
    public static String formatRetention(Duration window) {
        long seconds = (long) Units.totalSeconds(window);
        if (seconds != 0 && seconds % 86400 == 0) {
            return seconds / 86400 + "d";
        }
        return Units.formatDuration(window);
    }

    /** How long a class is kept, never shorter than its floor. */
    public Duration windowFor(String klass) {
        Duration window = windows.getOrDefault(klass, Duration.ZERO);
        Duration floor = floorFor(klass);
        return window.compareTo(floor) >= 0 ? window : floor;
    }

    /** Anything older than this may go, holds permitting. */
    public NanoTime cutoffFor(String klass, NanoTime now) {
        double seconds = Units.totalSeconds(windowFor(klass));
        return now.shifted(-(long) (seconds * NanoTime.NS_PER_SECOND));
    }

    /** One line, every class's window in days. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        for (String klass : new TreeSet<>(windows.keySet())) {
            parts.add(klass + " " + formatRetention(windowFor(klass)));
        }
        return "retention " + String.join(", ", parts);
    }

    /** The whole question about one record, in the order that matters. */
    public static RetentionDecision decide(
            RetentionPolicy policy, List<Hold> holds, String klass, NanoTime when, NanoTime now) {
        for (Hold hold : holds) {
            if (hold.covers(when)) {
                return new RetentionDecision(false, "held", hold.name());
            }
        }
        NanoTime cutoff = policy.cutoffFor(klass, now);
        if (when.compareTo(cutoff) >= 0) {
            return new RetentionDecision(false, "inside the window", "");
        }
        return new RetentionDecision(true, "older than the window", "");
    }

    /** Build the policy from the retention block, over the defaults. */
    public static RetentionPolicy policyFromMapping(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ConfigError("the retention section must be a mapping");
        }
        Map<String, Duration> windows = new LinkedHashMap<>(DEFAULT_WINDOWS);
        map.forEach((klass, value) ->
                windows.put(io.github.rkaushikdev.trammelworks.Coerce.text(klass), Units.parseDuration(value)));
        return new RetentionPolicy(windows);
    }
}
