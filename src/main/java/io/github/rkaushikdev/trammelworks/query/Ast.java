package io.github.rkaushikdev.trammelworks.query;

import io.github.rkaushikdev.trammelworks.QueryError;
import io.github.rkaushikdev.trammelworks.Text;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** The fields a filter may name, and the walks every consumer of the tree shares. */
public final class Ast {

    /**
     * The fields a filter may name. Anything else is a typo, and a typo that silently matched nothing
     * would be read as "there were no such records".
     */
    public static final Set<String> FIELDS = Set.of("site", "unit", "class", "stream", "message", "confidence");

    /** What a label field starts with. */
    public static final String LABEL_PREFIX = "label.";

    /** The operators that only make sense on text. */
    public static final Set<Op> TEXT_ONLY = Set.of(Op.MATCH, Op.NOT_MATCH);

    private Ast() {}

    /** Validate a field name, allowing any {@code label.x}. */
    public static String checkField(String name) {
        String cleaned = Text.strip(name).toLowerCase(Locale.ROOT);
        if (cleaned.startsWith(LABEL_PREFIX)) {
            if (cleaned.length() == LABEL_PREFIX.length()) {
                throw new QueryError("a label needs a name", name);
            }
            return cleaned;
        }
        if (!FIELDS.contains(cleaned)) {
            throw new QueryError("unknown field", name);
        }
        return cleaned;
    }

    /** Whether the lexer would read this as exactly one word or number. */
    public static boolean bareToken(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (Pattern pattern : List.of(Lexer.WORD_PATTERN, Lexer.NUMBER_PATTERN)) {
            if (pattern.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Quote a value unless the lexer would read it back whole and unchanged. The test is the
     * lexer's own patterns rather than a character set written out again here, because a second copy
     * drifts, and the damage is a filter that renders into text the parser then refuses. A value that
     * spells a keyword is quoted too: {@code unit = and} is three tokens and none of them is the
     * value somebody meant.
     */
    public static String quote(String value) {
        if (bareToken(value) && !Lexer.KEYWORDS.contains(value.toLowerCase(Locale.ROOT))) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Every node in the tree, parents before children. */
    public static List<Node> walk(Node node) {
        List<Node> out = new ArrayList<>();
        out.add(node);
        switch (node) {
            case And both -> {
                out.addAll(walk(both.left()));
                out.addAll(walk(both.right()));
            }
            case Or either -> {
                out.addAll(walk(either.left()));
                out.addAll(walk(either.right()));
            }
            case Not inverse -> out.addAll(walk(inverse.inner()));
            default -> {}
        }
        return out;
    }

    /** Which fields a tree touches, for the planner and the doctor. */
    public static Set<String> fieldsUsed(Node node) {
        Set<String> found = new HashSet<>();
        if (node == null) {
            return Set.of();
        }
        for (Node item : walk(node)) {
            if (item instanceof Compare compare) {
                found.add(compare.field());
            } else if (item instanceof InSet set) {
                found.add(set.field());
            }
        }
        return Set.copyOf(found);
    }
}
