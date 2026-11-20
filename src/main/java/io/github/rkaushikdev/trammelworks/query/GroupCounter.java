package io.github.rkaushikdev.trammelworks.query;

import io.github.rkaushikdev.trammelworks.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds groups a row at a time. */
public final class GroupCounter {

    private final Map<String, Long> counts = new LinkedHashMap<>();
    private final Map<String, Long> first = new LinkedHashMap<>();
    private final Map<String, Long> last = new LinkedHashMap<>();

    /** Count one record seen at {@code atNs}. */
    public void add(String key, long atNs) {
        add(key, atNs, 1);
    }

    /** Count {@code count} records, widening the group's span to take in {@code atNs}. */
    public void add(String key, long atNs, long count) {
        counts.merge(key, count, Long::sum);
        if (!first.containsKey(key) || atNs < first.get(key)) {
            first.put(key, atNs);
        }
        if (!last.containsKey(key) || atNs > last.get(key)) {
            last.put(key, atNs);
        }
    }

    /** Set a group outright, as the database counted it. */
    void put(String key, long count, long firstNs, long lastNs) {
        counts.put(key, count);
        first.put(key, firstNs);
        last.put(key, lastNs);
    }

    /** Biggest first, then by key so equal counts come back in a fixed order. */
    public List<Group> groups() {
        List<Group> built = new ArrayList<>();
        counts.forEach((key, count) ->
                built.add(new Group(key, count, first.getOrDefault(key, 0L), last.getOrDefault(key, 0L))));
        built.sort(
                Comparator.comparingLong(Group::count).reversed().thenComparing(Group::key, Json::compareCodePoints));
        return built;
    }
}
