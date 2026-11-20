package io.github.rkaushikdev.trammelworks;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The checks every config section shares.
 *
 * <p>Unknown keys are refused rather than ignored. A config file is the only way to change what an
 * unattended box does, so a key that is quietly skipped is a setting somebody believes is in force
 * and is not. A rejected key is nearly always a typo of a real one, and the parser knows the real
 * ones, so it says which.
 */
public final class Sections {

    /** How alike a key has to be to a real one before it is worth suggesting. */
    public static final double SUGGESTION_CUTOFF = 0.6;

    private Sections() {}

    /** The real key a rejected one was probably meant to be, or an empty string. */
    public static String suggest(String name, Collection<String> known) {
        List<String> ordered = new TreeSet<>(known).stream().toList();
        return CloseMatches.best(name, ordered, SUGGESTION_CUTOFF).orElse("");
    }

    /**
     * Refuse a section carrying any key this build does not know.
     *
     * @return {@code raw} itself, so the check can sit at the top of a builder as an expression
     */
    public static <M extends Map<String, ?>> M checkKeys(M raw, Set<String> known, String section) {
        TreeSet<String> unknown = new TreeSet<>(Json::compareCodePoints);
        for (String key : raw.keySet()) {
            if (!known.contains(key)) {
                unknown.add(key);
            }
        }
        if (unknown.isEmpty()) {
            return raw;
        }
        String hint = unknown.size() == 1 ? suggest(unknown.first(), known) : "";
        String detail = String.join(", ", unknown);
        if (!hint.isEmpty()) {
            detail = detail + ", did you mean " + hint;
        }
        throw new ConfigError("unknown " + section + " key", detail);
    }

    /** One nested block, checked to be a block and defaulted to an empty one. */
    public static Map<String, Object> section(Map<String, ?> raw, String name, String owner) {
        Object found = raw.get(name);
        if (found == null) {
            // An empty block in YAML reads as null rather than as an empty mapping, and writing
            // the key with nothing under it is how somebody says "defaults please".
            return new LinkedHashMap<>();
        }
        return mapping(found, "the " + name + " section must be a mapping", owner);
    }

    /** A value that has to be a mapping, with string keys, or the given refusal. */
    public static Map<String, Object> mapping(Object found, String refusal, String detail) {
        if (!(found instanceof Map<?, ?> map)) {
            throw new ConfigError(refusal, detail);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(keyName(key), value));
        return out;
    }

    private static String keyName(Object key) {
        if (key == null) {
            return "None";
        }
        if (key instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        return key.toString();
    }

    /** A key that has to be there, named by the key itself in the refusal. */
    public static Object require(Map<String, ?> raw, String name, String sectionName) {
        return require(raw, name, sectionName, "");
    }

    /**
     * A key that has to be there. {@code noun} lets a section keep the words it already used:
     * "config has no site id" reads better than the key name would.
     */
    public static Object require(Map<String, ?> raw, String name, String sectionName, String noun) {
        if (!raw.containsKey(name)) {
            throw new ConfigError(sectionName + " has no " + (noun.isEmpty() ? name : noun));
        }
        return raw.get(name);
    }
}
