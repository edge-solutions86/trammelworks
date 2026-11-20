package io.github.rkaushikdev.trammelworks.redact;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Sections;
import io.github.rkaushikdev.trammelworks.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;

/** Applying site rules, and building them from their config blocks. */
public final class Rules {

    static final Set<String> RULE_KEYS = Set.of("pattern", "action", "keep_first", "keep_last", "marker");

    private Rules() {}

    /**
     * A line after one rule, and what it caught.
     *
     * @param text the new line
     * @param hits every place the rule fired
     */
    public record Applied(String text, List<RuleHit> hits) {

        /** Copies the hits. */
        public Applied {
            hits = List.copyOf(hits);
        }
    }

    /** Run one rule over a line. */
    public static Applied applyRule(Rule rule, String text) {
        List<RuleHit> hits = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        Matcher match = rule.compiled().pattern().matcher(text);
        while (match.find()) {
            int[] span = rule.targetSpan(match);
            int start = span[0];
            int end = span[1];
            if (start < 0 || start < cursor) {
                continue;
            }
            String value = text.substring(start, end);
            hits.add(new RuleHit(rule.name(), start, end, value));
            out.append(text, cursor, start);
            out.append(rule.replacementFor(value));
            cursor = end;
        }
        out.append(text.substring(cursor));
        return new Applied(out.toString(), hits);
    }

    /** Build one rule from its config block. */
    public static Rule ruleFromMapping(String name, Map<String, ?> raw) {
        TreeSet<String> unknown = new TreeSet<>(raw.keySet());
        unknown.removeAll(RULE_KEYS);
        if (!unknown.isEmpty()) {
            throw new ConfigError("unknown rule key", String.join(", ", unknown));
        }
        if (!raw.containsKey("pattern")) {
            throw new ConfigError("rule has no pattern", name);
        }
        Object actionRaw = raw.containsKey("action") ? raw.get("action") : Action.DROP.value();
        String actionText = Text.strip(Coerce.text(actionRaw)).toLowerCase(Locale.ROOT);
        Action action =
                Action.fromValue(actionText).orElseThrow(() -> new ConfigError("unknown rule action", actionText));
        return new Rule(
                name,
                Coerce.text(raw.get("pattern")),
                action,
                Coerce.toInt(raw.containsKey("keep_first") ? raw.get("keep_first") : 0),
                Coerce.toInt(raw.containsKey("keep_last") ? raw.get("keep_last") : 0),
                Coerce.text(raw.containsKey("marker") ? raw.get("marker") : Rule.DEFAULT_MARKER));
    }

    /** Build the whole rule list, in the order the config wrote them. */
    public static List<Rule> rulesFromMapping(Map<String, ?> raw) {
        List<Rule> out = new ArrayList<>();
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            out.add(ruleFromMapping(
                    entry.getKey(), Sections.mapping(entry.getValue(), "a rule must be a mapping", entry.getKey())));
        }
        return out;
    }
}
