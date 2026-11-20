package io.github.rkaushikdev.trammelworks.query;

import io.github.rkaushikdev.trammelworks.Repr;
import java.util.Locale;

/**
 * One token and where it was, counted in characters from the start of the expression.
 *
 * @param kind what the token is
 * @param text the token's text, with a string's quotes and escapes removed
 * @param position where it was
 */
public record Token(Kind kind, String text, int position) {

    /** Whether this is a word the language reserves. */
    public boolean isKeyword() {
        return kind == Kind.WORD && Lexer.KEYWORDS.contains(lower());
    }

    /** The text in lower case, as keywords are compared. */
    public String lower() {
        return text.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return kind.value() + " " + Repr.of(text) + " at " + position;
    }
}
