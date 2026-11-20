package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Sections;
import io.github.rkaushikdev.trammelworks.SiteRegex;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/**
 * How one unit's own multi line output is framed into events.
 *
 * <p>Different from chunk joining: a stack trace really is eight lines the application wrote, and
 * whether they are one event is a judgement about that application. A rule says it one of two
 * ways: a start pattern (a line matching it begins a new event) or a continuation pattern (a line
 * matching it belongs to the event above). Both at once is refused, because a config giving both is
 * one where the reviewer could not tell which was meant.
 */
public final class MultilineRule {

    /** How many lines one event may hold by default. */
    public static final int DEFAULT_MAX_LINES = 200;

    static final Set<String> KEYS = Set.of("start", "continuation", "max_lines", "negate");

    private final String unit;
    private final String start;
    private final String continuation;
    private final int maxLines;
    private final boolean negate;
    private final SiteRegex compiled;

    /** A rule with everything given; exactly one of {@code start} and {@code continuation} is set. */
    public MultilineRule(String unit, String start, String continuation, int maxLines, boolean negate) {
        if ((start == null) == (continuation == null)) {
            throw new ConfigError("a multiline rule needs exactly one of start or continuation", unit);
        }
        if (maxLines < 2) {
            throw new ConfigError("max_lines must be at least 2", Integer.toString(maxLines));
        }
        try {
            this.compiled = SiteRegex.compile(start != null ? start : continuation);
        } catch (PatternSyntaxException exc) {
            throw new ConfigError("multiline pattern does not compile", exc.getDescription());
        }
        this.unit = unit;
        this.start = start;
        this.continuation = continuation;
        this.maxLines = maxLines;
        this.negate = negate;
    }

    /** A rule where a line matching {@code pattern} begins a new event. */
    public static MultilineRule starting(String unit, String pattern) {
        return new MultilineRule(unit, pattern, null, DEFAULT_MAX_LINES, false);
    }

    /** A rule where a line matching {@code pattern} continues the event above. */
    public static MultilineRule continuing(String unit, String pattern) {
        return new MultilineRule(unit, null, pattern, DEFAULT_MAX_LINES, false);
    }

    /** The unit the rule applies to. */
    public String unit() {
        return unit;
    }

    /** The start pattern, when the rule is framed that way. */
    public Optional<String> start() {
        return Optional.ofNullable(start);
    }

    /** The continuation pattern, when the rule is framed that way. */
    public Optional<String> continuation() {
        return Optional.ofNullable(continuation);
    }

    /** The most lines one event may hold. */
    public int maxLines() {
        return maxLines;
    }

    /** Whether the pattern's sense is flipped. */
    public boolean negate() {
        return negate;
    }

    /** The same rule with a different line cap. */
    public MultilineRule withMaxLines(int value) {
        return new MultilineRule(unit, start, continuation, value, negate);
    }

    /** The same rule with the pattern's sense flipped or not. */
    public MultilineRule withNegate(boolean value) {
        return new MultilineRule(unit, start, continuation, maxLines, value);
    }

    /** True when this line belongs to the event above it. */
    public boolean continues(String message) {
        boolean matched = compiled.search(message);
        if (negate) {
            matched = !matched;
        }
        return continuation != null ? matched : !matched;
    }

    /** Build the per unit rule table from the config section. */
    public static Map<String, MultilineRule> rulesFromMapping(Map<String, ?> raw) {
        Map<String, MultilineRule> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            String unit = entry.getKey();
            Map<String, Object> body = Sections.mapping(entry.getValue(), "a multiline rule must be a mapping", unit);
            Sections.checkKeys(body, KEYS, "multiline");
            Object start = body.get("start");
            Object continuation = body.get("continuation");
            out.put(
                    unit,
                    new MultilineRule(
                            unit,
                            start == null ? null : Coerce.text(start),
                            continuation == null ? null : Coerce.text(continuation),
                            Coerce.toInt(body.containsKey("max_lines") ? body.get("max_lines") : DEFAULT_MAX_LINES),
                            body.containsKey("negate") && Coerce.toBool(body.get("negate"))));
        }
        return out;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MultilineRule rule
                && unit.equals(rule.unit)
                && Objects.equals(start, rule.start)
                && Objects.equals(continuation, rule.continuation)
                && maxLines == rule.maxLines
                && negate == rule.negate;
    }

    @Override
    public int hashCode() {
        return Objects.hash(unit, start, continuation, maxLines, negate);
    }

    @Override
    public String toString() {
        return "MultilineRule[unit=" + unit + ", start=" + start + ", continuation=" + continuation + "]";
    }
}
