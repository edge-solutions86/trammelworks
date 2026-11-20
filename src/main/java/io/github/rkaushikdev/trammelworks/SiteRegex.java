package io.github.rkaushikdev.trammelworks;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A regular expression as a commissioning engineer writes it in a site config.
 *
 * <p>Site config patterns use a dialect of their own:
 * {@code (?P<name>...)} groups, {@code (?P=name)} back references, {@code \Z}, {@code {,n}}
 * quantifiers, literal brackets inside a class, and Unicode aware {@code \d}, {@code \w}, {@code
 * \s} and {@code \b}. This translates that dialect into a {@link Pattern} and keeps the group names
 * as the config wrote them, so a rule can still ask for its {@code secret} group.
 */
public final class SiteRegex {

    private final String source;
    private final Pattern pattern;
    private final Map<String, String> names;

    private SiteRegex(String source, Pattern pattern, Map<String, String> names) {
        this.source = source;
        this.pattern = pattern;
        this.names = names;
    }

    /**
     * Translate and compile.
     *
     * @throws PatternSyntaxException when the pattern is not valid
     */
    public static SiteRegex compile(String source) {
        Map<String, String> names = new LinkedHashMap<>();
        String translated = translate(source, names);
        Pattern compiled = Pattern.compile(translated, Pattern.UNICODE_CHARACTER_CLASS);
        return new SiteRegex(source, compiled, Collections.unmodifiableMap(names));
    }

    /** The pattern as the config wrote it. */
    public String source() {
        return source;
    }

    /** The compiled pattern. */
    public Pattern pattern() {
        return pattern;
    }

    /** How many capturing groups the pattern has. */
    public int groups() {
        return pattern.matcher("").groupCount();
    }

    /** Whether the pattern names a group, by the name the config used. */
    public boolean hasGroup(String name) {
        return names.containsKey(name);
    }

    /** Where a named group starts in a match, or -1 when it took no part. */
    public int start(Matcher match, String name) {
        return match.start(names.get(name));
    }

    /** Where a named group ends in a match, or -1 when it took no part. */
    public int end(Matcher match, String name) {
        return match.end(names.get(name));
    }

    /** Whether the pattern matches at the start of {@code text}. */
    public boolean matchesStart(CharSequence text) {
        return pattern.matcher(text).lookingAt();
    }

    /** Whether the pattern matches anywhere in {@code text}. */
    public boolean search(CharSequence text) {
        return pattern.matcher(text).find();
    }

    private static String translate(String source, Map<String, String> names) {
        StringBuilder out = new StringBuilder();
        boolean inClass = false;
        int index = 0;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (c == '\\' && index + 1 < source.length()) {
                char next = source.charAt(index + 1);
                out.append(!inClass && next == 'Z' ? "\\z" : "\\" + next);
                index += 2;
                continue;
            }
            if (inClass) {
                if (c == ']' && !classJustOpened(out)) {
                    inClass = false;
                    out.append(c);
                } else if (c == '[') {
                    out.append("\\[");
                } else if (c == '&' && index + 1 < source.length() && source.charAt(index + 1) == '&') {
                    out.append("&\\&");
                    index++;
                } else {
                    out.append(c);
                }
                index++;
                continue;
            }
            if (c == '[') {
                inClass = true;
                out.append(c);
                index++;
                if (index < source.length() && source.charAt(index) == '^') {
                    out.append('^');
                    index++;
                }
                if (index < source.length() && source.charAt(index) == ']') {
                    out.append("\\]");
                    index++;
                }
                continue;
            }
            if (source.startsWith("(?P<", index)) {
                int close = source.indexOf('>', index);
                if (close < 0) {
                    throw new PatternSyntaxException("missing >, unterminated name", source, index);
                }
                String name = source.substring(index + 4, close);
                String javaName = "g" + names.size() + name.replaceAll("[^A-Za-z0-9]", "");
                if (names.put(name, javaName) != null) {
                    throw new PatternSyntaxException("redefinition of group name " + Repr.of(name), source, index);
                }
                out.append("(?<").append(javaName).append('>');
                index = close + 1;
                continue;
            }
            if (source.startsWith("(?P=", index)) {
                int close = source.indexOf(')', index);
                String name = close < 0 ? "" : source.substring(index + 4, close);
                String javaName = names.get(name);
                if (javaName == null) {
                    throw new PatternSyntaxException("unknown group name " + Repr.of(name), source, index);
                }
                out.append("\\k<").append(javaName).append('>');
                index = close + 1;
                continue;
            }
            if (source.startsWith("(?#", index)) {
                int close = source.indexOf(')', index);
                if (close < 0) {
                    throw new PatternSyntaxException("missing ), unterminated comment", source, index);
                }
                index = close + 1;
                continue;
            }
            if (c == '{' && index + 1 < source.length() && source.charAt(index + 1) == ',') {
                Matcher bound = Pattern.compile("\\{,(\\d+)}").matcher(source).region(index, source.length());
                if (bound.lookingAt()) {
                    out.append("{0,").append(bound.group(1)).append('}');
                    index = bound.end();
                    continue;
                }
            }
            out.append(c);
            index++;
        }
        return out.toString();
    }

    private static boolean classJustOpened(StringBuilder out) {
        int length = out.length();
        return (length >= 1 && out.charAt(length - 1) == '[' && !escaped(out, length - 1))
                || (length >= 2
                        && out.charAt(length - 1) == '^'
                        && out.charAt(length - 2) == '['
                        && !escaped(out, length - 2));
    }

    private static boolean escaped(StringBuilder out, int position) {
        int slashes = 0;
        for (int index = position - 1; index >= 0 && out.charAt(index) == '\\'; index--) {
            slashes++;
        }
        return slashes % 2 == 1;
    }

    @Override
    public String toString() {
        return source;
    }
}
