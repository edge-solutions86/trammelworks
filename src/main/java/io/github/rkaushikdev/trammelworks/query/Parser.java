package io.github.rkaushikdev.trammelworks.query;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.QueryError;
import io.github.rkaushikdev.trammelworks.Units;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Turning tokens into a tree.
 *
 * <p>Recursive descent, three levels of precedence: {@code or} binds loosest, then {@code and},
 * then {@code not}. {@code and} is also implied between two adjacent terms: {@code unit = x since
 * 2h} reads naturally and there is no other sensible meaning for two conditions side by side.
 *
 * <p>Times are read here rather than in the evaluator, because {@code since 2h} means two hours
 * before <em>now</em> and the tree has to be able to say what instant that was. A tree that carried
 * the words instead would give different answers depending on when it was walked, and a report
 * would not be reproducible.
 */
public final class Parser {

    /** The kinds of token that can stand as a value. */
    public static final Set<Kind> VALUE_KINDS = Set.of(Kind.WORD, Kind.STRING, Kind.NUMBER);

    private static final long NS_PER_SECOND = 1_000_000_000L;

    private final List<Token> tokens;
    private final NanoTime now;
    private int index;

    /** A parser over {@code tokens}, reading relative times back from {@code now}. */
    public Parser(List<Token> tokens, NanoTime now) {
        this.tokens = List.copyOf(tokens);
        this.now = now;
    }

    /** Parse a whole filter expression. */
    public static Query parseQuery(String text, NanoTime now) {
        return new Parser(Lexer.tokenize(text), now).parse();
    }

    /** Read either a duration back from now, or an absolute timestamp, as nanoseconds. */
    public static long parseInstant(String text, NanoTime now) {
        try {
            return NanoTime.parse(text).epochNs();
        } catch (ParseError | QueryError exc) {
            // Not a timestamp, so it has to be a duration.
        }
        Duration window;
        try {
            window = Units.parseDuration(text);
        } catch (ConfigError exc) {
            throw new QueryError("not a time or a duration", text, exc);
        }
        return now.shifted(-(long) (Units.totalSeconds(window) * NS_PER_SECOND)).epochNs();
    }

    /** The token being looked at. */
    public Token current() {
        return tokens.get(index);
    }

    /** Move past the current token, never past the end. */
    public Token advance() {
        Token token = current();
        if (token.kind() != Kind.END) {
            index += 1;
        }
        return token;
    }

    private Token expect(Kind kind, String what) {
        if (current().kind() != kind) {
            throw new QueryError("expected " + what, current().toString());
        }
        return advance();
    }

    private boolean atKeyword(String word) {
        return current().isKeyword() && current().lower().equals(word);
    }

    /** The whole query, refusing anything left over. */
    public Query parse() {
        // "limit 5" on its own is a whole query. Without this check the limit keyword would be
        // read as a field name and refused, which is a strange thing to say about a word the
        // language defines.
        boolean empty = current().kind() == Kind.END || atKeyword("limit");
        Optional<Node> where = empty ? Optional.empty() : Optional.of(parseOr());
        OptionalInt limit = parseLimit();
        if (current().kind() != Kind.END) {
            throw new QueryError("unexpected trailing input", current().toString());
        }
        return new Query(where, limit);
    }

    private OptionalInt parseLimit() {
        if (!atKeyword("limit")) {
            return OptionalInt.empty();
        }
        advance();
        Token token = current();
        if (token.kind() != Kind.NUMBER || !token.text().codePoints().allMatch(Character::isDigit)) {
            throw new QueryError("limit needs a whole number", token.toString());
        }
        advance();
        long value = 0;
        for (int point : token.text().codePoints().toArray()) {
            value = value * 10 + Character.digit(point, 10);
            if (value > Integer.MAX_VALUE) {
                throw new QueryError("limit is too large", token.text());
            }
        }
        if (value < 1) {
            throw new QueryError("limit must be at least one", token.text());
        }
        return OptionalInt.of((int) value);
    }

    private Node parseOr() {
        Node left = parseAnd();
        while (atKeyword("or")) {
            advance();
            left = new Or(left, parseAnd());
        }
        return left;
    }

    private Node parseAnd() {
        Node left = parseUnary();
        while (true) {
            if (atKeyword("and")) {
                advance();
                left = new And(left, parseUnary());
                continue;
            }
            if (startsATerm()) {
                left = new And(left, parseUnary());
                continue;
            }
            return left;
        }
    }

    /** True when the next token could begin another condition. */
    private boolean startsATerm() {
        Token token = current();
        if (token.kind() == Kind.LPAREN) {
            return true;
        }
        if (token.kind() != Kind.WORD) {
            return false;
        }
        if (token.isKeyword()) {
            return Set.of("not", "since", "until").contains(token.lower());
        }
        return true;
    }

    private Node parseUnary() {
        if (atKeyword("not")) {
            advance();
            return new Not(parseUnary());
        }
        return parsePrimary();
    }

    private Node parsePrimary() {
        if (current().kind() == Kind.LPAREN) {
            advance();
            Node inner = parseOr();
            expect(Kind.RPAREN, "a closing parenthesis");
            return inner;
        }
        if (atKeyword("since")) {
            advance();
            Token token = valueToken();
            return new Since(parseInstant(token.text(), now), token.text());
        }
        if (atKeyword("until")) {
            advance();
            Token token = valueToken();
            return new Until(parseInstant(token.text(), now), token.text());
        }
        return parseComparison();
    }

    private Node parseComparison() {
        Token field = current();
        if (field.kind() != Kind.WORD || field.isKeyword()) {
            throw new QueryError("expected a field name", field.toString());
        }
        advance();
        if (atKeyword("in")) {
            advance();
            return new InSet(field.text(), valueList());
        }
        if (atKeyword("not")) {
            // "unit not in (a, b)" rather than "not unit in (a, b)". Both mean the same thing and
            // only one of them is how anybody says it.
            advance();
            if (!atKeyword("in")) {
                throw new QueryError("expected in after not", current().toString());
            }
            advance();
            return new InSet(field.text(), valueList(), true);
        }
        Token operator = current();
        if (operator.kind() != Kind.OPERATOR) {
            throw new QueryError("expected a comparison operator", operator.toString());
        }
        advance();
        Token value = valueToken();
        return new Compare(field.text(), Op.of(operator.text()), value.text());
    }

    private Token valueToken() {
        Token token = current();
        if (!VALUE_KINDS.contains(token.kind())) {
            throw new QueryError("expected a value", token.toString());
        }
        return advance();
    }

    private List<String> valueList() {
        expect(Kind.LPAREN, "an opening parenthesis");
        List<String> values = new ArrayList<>();
        values.add(valueToken().text());
        while (current().kind() == Kind.COMMA) {
            advance();
            values.add(valueToken().text());
        }
        expect(Kind.RPAREN, "a closing parenthesis");
        return values;
    }
}
