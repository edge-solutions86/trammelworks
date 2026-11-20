package io.github.rkaushikdev.trammelworks;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, insertion ordered copies of the small maps records carry as labels. */
public final class Maps {

    private Maps() {}

    /** An unmodifiable copy that keeps the source's iteration order. */
    public static <K, V> Map<K, V> frozen(Map<? extends K, ? extends V> source) {
        if (source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /** An ordered map from alternating keys and values. */
    public static Map<String, Object> of(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("keys and values must come in pairs");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            out.put((String) pairs[index], pairs[index + 1]);
        }
        return out;
    }
}
