package io.github.rkaushikdev.trammelworks.query;

import io.github.rkaushikdev.trammelworks.QueryError;
import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Breaking a filter expression into tokens.
 *
 * <p>The language exists because the alternative is a support engineer typing SQL against the
 * archive over a VPN at two in the morning, and the archive is the one file on the box that must not
 * be experimented on. A filter that cannot express {@code DELETE} is worth a parser. It is written
 * to be typed by hand:
 *
 * <pre>
 * unit = payment-kernel and message ~ declined
 * class = payment and since 2h and not message ~ heartbeat
 * unit in (pump-bridge, tank-gauge) and stream = stderr
 * </pre>
 *
 * <p>So bare words are values: {@code payment-kernel} needs no quotes even though it has a dash in
 * it. Every token keeps the position it came from, because a parse error that says only "bad
 * syntax" is useless on a long expression.
 */
public final class Lexer {

    /** The words the language reserves, matched without case. */
    public static final Set<String> KEYWORDS = Set.of("and", "or", "not", "in", "since", "until", "limit");

    /** The comparison operators, two character ones first so they win. */
    public static final List<String> OPERATORS = List.of("!~", "!=", ">=", "<=", "~", "=", ">", "<");

    /** A bare word: a field name, a keyword or an unquoted value. */
    public static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.:\\-]*");

    /**
     * A token that opens with a digit runs to the end of the run, letters included, so that {@code
     * 2h}, {@code 250ms} and {@code 2025-03-04T12:00:00Z} are each one token. Splitting {@code 2h}
     * would make the parser glue the pieces back together with spacing it no longer has.
     */
    public static final Pattern NUMBER_PATTERN = Pattern.compile("\\p{Nd}[A-Za-z0-9.:\\-+]*");

    private Lexer() {}

    /** Break an expression into tokens, ending with an {@link Kind#END} token. */
    public static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int character = text.codePointAt(index);
            if (Text.isSpace(character)) {
                index += Character.charCount(character);
                continue;
            }
            if (character == '"' || character == '\'') {
                StringBuilder value = new StringBuilder();
                index = readString(text, index, value);
                tokens.add(new Token(Kind.STRING, value.toString(), position(text, index)));
                continue;
            }
            Kind single =
                    switch (character) {
                        case '(' -> Kind.LPAREN;
                        case ')' -> Kind.RPAREN;
                        case ',' -> Kind.COMMA;
                        default -> null;
                    };
            if (single != null) {
                tokens.add(new Token(single, Character.toString(character), position(text, index)));
                index += 1;
                continue;
            }
            String operator = matchOperator(text, index);
            if (operator != null) {
                tokens.add(new Token(Kind.OPERATOR, operator, position(text, index)));
                index += operator.length();
                continue;
            }
            int end = lookingAt(WORD_PATTERN, text, index);
            Kind kind = Kind.WORD;
            if (end < 0) {
                end = lookingAt(NUMBER_PATTERN, text, index);
                kind = Kind.NUMBER;
            }
            if (end < 0) {
                throw new QueryError(
                        "unexpected character",
                        Repr.of(Character.toString(character)) + " at " + position(text, index));
            }
            tokens.add(new Token(kind, text.substring(index, end), position(text, index)));
            index = end;
        }
        tokens.add(new Token(Kind.END, "", position(text, text.length())));
        return tokens;
    }

    /** A one line rendering, used by the error messages and the tests. */
    public static String describe(List<Token> tokens) {
        return tokens.stream()
                .filter(token -> token.kind() != Kind.END)
                .map(Token::text)
                .collect(Collectors.joining(" "));
    }

    private static int position(String text, int index) {
        return text.codePointCount(0, index);
    }

    /** Read a quoted value into {@code out}, honouring a backslash escape; returns the index after it. */
    private static int readString(String text, int start, StringBuilder out) {
        int quote = text.codePointAt(start);
        int index = start + 1;
        while (index < text.length()) {
            int character = text.codePointAt(index);
            int width = Character.charCount(character);
            if (character == '\\' && index + width < text.length()) {
                int escaped = text.codePointAt(index + width);
                out.appendCodePoint(escaped);
                index += width + Character.charCount(escaped);
                continue;
            }
            if (character == quote) {
                return index + width;
            }
            out.appendCodePoint(character);
            index += width;
        }
        throw new QueryError("unclosed quote", "at " + position(text, start));
    }

    private static String matchOperator(String text, int index) {
        for (String operator : OPERATORS) {
            if (text.startsWith(operator, index)) {
                return operator;
            }
        }
        return null;
    }

    private static int lookingAt(Pattern pattern, String text, int index) {
        Matcher match = pattern.matcher(text).region(index, text.length());
        return match.lookingAt() ? match.end() : -1;
    }
}
