package io.github.rkaushikdev.trammelworks.redact;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.SiteRegex;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.PatternSyntaxException;

/**
 * One site written redaction rule: a name, a pattern, and what to do with what it matched.
 *
 * <p>The name is not decoration: the audit trail counts by name, so a rule that fires ten thousand
 * times a day is visible as itself. A rule may name a {@code secret} group, or have a single group,
 * to act on; otherwise the whole match is acted on, which is nearly always too much, since {@code
 * driver=(\d+)} should redact the number and keep the word {@code driver}.
 */
public final class Rule {

    /** The marker a drop rule writes. */
    public static final String DEFAULT_MARKER = "[redacted]";

    /** What a masked character becomes. */
    public static final char MASK_CHARACTER = '*';

    /** The group a rule with several groups must name. */
    public static final String GROUP_NAME = "secret";

    private final String name;
    private final String pattern;
    private final Action action;
    private final int keepFirst;
    private final int keepLast;
    private final String marker;
    private final SiteRegex compiled;

    /** A drop rule writing the default marker. */
    public Rule(String name, String pattern) {
        this(name, pattern, Action.DROP, 0, 0, DEFAULT_MARKER);
    }

    /** A rule with every setting given. */
    public Rule(String name, String pattern, Action action, int keepFirst, int keepLast, String marker) {
        String bare = name.replace("_", "").replace("-", "");
        if (name.isEmpty() || bare.isEmpty() || !bare.codePoints().allMatch(Character::isLetterOrDigit)) {
            throw new ConfigError("malformed rule name", name);
        }
        try {
            this.compiled = SiteRegex.compile(pattern);
        } catch (PatternSyntaxException exc) {
            throw new ConfigError("rule pattern does not compile", exc.getDescription());
        }
        if (compiled.groups() > 1 && !compiled.hasGroup(GROUP_NAME)) {
            throw new ConfigError("a rule with several groups must name one 'secret'", name);
        }
        if (keepFirst < 0 || keepLast < 0) {
            throw new ConfigError("cannot keep a negative number of characters", name);
        }
        if (action == Action.DROP && (keepFirst != 0 || keepLast != 0)) {
            throw new ConfigError("a drop rule cannot keep characters", name);
        }
        this.name = name;
        this.pattern = pattern;
        this.action = action;
        this.keepFirst = keepFirst;
        this.keepLast = keepLast;
        this.marker = marker;
    }

    /** A mask rule keeping the given characters. */
    public static Rule mask(String name, String pattern, int keepFirst, int keepLast) {
        return new Rule(name, pattern, Action.MASK, keepFirst, keepLast, DEFAULT_MARKER);
    }

    /** A drop rule writing its own marker. */
    public static Rule drop(String name, String pattern, String marker) {
        return new Rule(name, pattern, Action.DROP, 0, 0, marker);
    }

    /** The name the audit counts by. */
    public String name() {
        return name;
    }

    /** The pattern as the config wrote it. */
    public String pattern() {
        return pattern;
    }

    /** What happens to a match. */
    public Action action() {
        return action;
    }

    /** Leading characters a mask keeps. */
    public int keepFirst() {
        return keepFirst;
    }

    /** Trailing characters a mask keeps. */
    public int keepLast() {
        return keepLast;
    }

    /** What a drop writes in place of the match. */
    public String marker() {
        return marker;
    }

    /** The compiled pattern. */
    public SiteRegex compiled() {
        return compiled;
    }

    /** Which part of a match this rule acts on, as start and end. */
    public int[] targetSpan(Matcher match) {
        if (compiled.hasGroup(GROUP_NAME)) {
            return new int[] {compiled.start(match, GROUP_NAME), compiled.end(match, GROUP_NAME)};
        }
        if (compiled.groups() == 1) {
            return new int[] {match.start(1), match.end(1)};
        }
        return new int[] {match.start(), match.end()};
    }

    /** What the matched value becomes. */
    public String replacementFor(String value) {
        if (action == Action.DROP) {
            return marker;
        }
        int hidden = value.length() - keepFirst - keepLast;
        String mask = String.valueOf(MASK_CHARACTER);
        if (hidden <= 0) {
            return mask.repeat(value.length());
        }
        String head = value.substring(0, keepFirst);
        String tail = keepLast > 0 ? value.substring(value.length() - keepLast) : "";
        return head + mask.repeat(hidden) + tail;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Rule rule
                && name.equals(rule.name)
                && pattern.equals(rule.pattern)
                && action == rule.action
                && keepFirst == rule.keepFirst
                && keepLast == rule.keepLast
                && marker.equals(rule.marker);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, pattern, action, keepFirst, keepLast, marker);
    }

    @Override
    public String toString() {
        return "Rule[name=" + name + ", pattern=" + pattern + ", action=" + action + "]";
    }
}
