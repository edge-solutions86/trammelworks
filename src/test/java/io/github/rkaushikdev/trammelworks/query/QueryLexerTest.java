package io.github.rkaushikdev.trammelworks.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.trammelworks.QueryError;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryLexerTest {

    static List<Kind> kinds(String text) {
        return Lexer.tokenize(text).stream().map(Token::kind).toList();
    }

    static List<String> texts(String text) {
        return Lexer.tokenize(text).stream()
                .filter(token -> token.kind() != Kind.END)
                .map(Token::text)
                .toList();
    }

    @Test
    void anEmptyExpressionIsJustTheEnd() {
        assertThat(kinds("")).containsExactly(Kind.END);
    }

    @Test
    void whitespaceAloneIsJustTheEnd() {
        assertThat(kinds("   \t ")).containsExactly(Kind.END);
    }

    @Test
    void aBareWordIsAWord() {
        assertThat(kinds("unit")).containsExactly(Kind.WORD, Kind.END);
    }

    @Test
    void aDashedValueNeedsNoQuotes() {
        assertThat(texts("unit = payment-kernel")).containsExactly("unit", "=", "payment-kernel");
    }

    @Test
    void aDottedLabelNameIsOneWord() {
        assertThat(texts("label.lane = 3")).containsExactly("label.lane", "=", "3");
    }

    @Test
    void everyComparisonOperatorIsRecognised() {
        for (String operator : List.of("=", "!=", "~", "!~", ">", "<", ">=", "<=")) {
            assertThat(texts("a " + operator + " b")).containsExactly("a", operator, "b");
        }
    }

    @Test
    void theTwoCharacterOperatorsWinOverTheOneCharacterOnes() {
        assertThat(texts("a >= b")).containsExactly("a", ">=", "b");
        assertThat(texts("a !~ b")).containsExactly("a", "!~", "b");
        assertThat(texts("a != b")).containsExactly("a", "!=", "b");
    }

    @Test
    void anOperatorNeedsNoSpacesAroundIt() {
        assertThat(texts("unit=payment-kernel")).containsExactly("unit", "=", "payment-kernel");
    }

    @Test
    void aDoubleQuotedStringIsOneToken() {
        List<Token> tokens = Lexer.tokenize("message ~ \"auth declined\"");
        assertThat(tokens.get(2).kind()).isEqualTo(Kind.STRING);
        assertThat(tokens.get(2).text()).isEqualTo("auth declined");
    }

    @Test
    void aSingleQuotedStringWorksToo() {
        assertThat(Lexer.tokenize("message ~ 'auth declined'").get(2).text()).isEqualTo("auth declined");
    }

    @Test
    void aQuoteCanBeEscapedInsideAString() {
        assertThat(Lexer.tokenize("message ~ \"say \\\"no\\\"\"").get(2).text()).isEqualTo("say \"no\"");
    }

    @Test
    void aBackslashCanBeEscaped() {
        assertThat(Lexer.tokenize("message ~ \"back\\\\slash\"").get(2).text()).isEqualTo("back\\slash");
    }

    @Test
    void anUnclosedQuoteIsRefused() {
        assertThatThrownBy(() -> Lexer.tokenize("message ~ \"never ends"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("unclosed quote");
    }

    @Test
    void anEmptyStringIsAllowed() {
        assertThat(Lexer.tokenize("message = \"\"").get(2).text()).isEmpty();
    }

    @Test
    void parenthesesAndCommasAreTheirOwnTokens() {
        assertThat(kinds("in (a, b)"))
                .containsExactly(Kind.WORD, Kind.LPAREN, Kind.WORD, Kind.COMMA, Kind.WORD, Kind.RPAREN, Kind.END);
    }

    @Test
    void aNumberIsANumber() {
        List<Token> tokens = Lexer.tokenize("limit 40");
        assertThat(tokens.get(1).kind()).isEqualTo(Kind.NUMBER);
        assertThat(tokens.get(1).text()).isEqualTo("40");
    }

    @Test
    void aTimestampReadsAsOneNumberToken() {
        List<Token> tokens = Lexer.tokenize("since 2025-03-04T12:00:00Z");
        assertThat(tokens.get(1).kind()).isEqualTo(Kind.NUMBER);
        assertThat(tokens.get(1).text()).isEqualTo("2025-03-04T12:00:00Z");
    }

    @Test
    void aDurationIsOneToken() {
        List<Token> tokens = Lexer.tokenize("since 2h");
        assertThat(tokens.get(1).kind()).isEqualTo(Kind.NUMBER);
        assertThat(tokens.get(1).text()).isEqualTo("2h");
    }

    @Test
    void aCompoundDurationIsOneToken() {
        assertThat(Lexer.tokenize("since 1d12h").get(1).text()).isEqualTo("1d12h");
    }

    @Test
    void aMillisecondDurationIsOneToken() {
        assertThat(Lexer.tokenize("since 250ms").get(1).text()).isEqualTo("250ms");
    }

    @Test
    void theKeywordsAreRecognised() {
        for (String word : List.of("and", "or", "not", "in", "since", "until", "limit")) {
            assertThat(Lexer.tokenize(word).get(0).isKeyword()).isTrue();
        }
    }

    @Test
    void aFieldNameIsNotAKeyword() {
        assertThat(Lexer.tokenize("unit").get(0).isKeyword()).isFalse();
    }

    @Test
    void keywordsAreMatchedWithoutCase() {
        assertThat(Lexer.tokenize("AND").get(0).isKeyword()).isTrue();
        assertThat(Lexer.tokenize("And").get(0).lower()).isEqualTo("and");
    }

    @Test
    void everyTokenKnowsWhereItWas() {
        List<Token> tokens = Lexer.tokenize("unit = pump-bridge");
        assertThat(tokens.get(0).position()).isZero();
        assertThat(tokens.get(1).position()).isEqualTo(5);
        assertThat(tokens.get(2).position()).isEqualTo(7);
    }

    @Test
    void theEndTokenSitsAtTheEnd() {
        String text = "unit = x";
        List<Token> tokens = Lexer.tokenize(text);
        assertThat(tokens.get(tokens.size() - 1).position()).isEqualTo(text.length());
    }

    @Test
    void anUnexpectedCharacterIsRefused() {
        assertThatThrownBy(() -> Lexer.tokenize("unit = $x"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("unexpected character");
    }

    @Test
    void theRefusalNamesThePosition() {
        QueryError caught = catchThrowableOfType(QueryError.class, () -> Lexer.tokenize("unit = $x"));
        assertThat(caught.detail())
                .hasValueSatisfying(detail -> assertThat(detail).contains("at 7"));
    }

    @Test
    void aWholeExpressionTokenizes() {
        String text = "unit = payment-kernel and message ~ \"declined\" and since 2h";
        assertThat(texts(text))
                .containsExactly(
                        "unit", "=", "payment-kernel", "and", "message", "~", "declined", "and", "since", "2h");
    }

    @Test
    void describeReadsTheExpressionBack() {
        assertThat(Lexer.describe(Lexer.tokenize("unit = x and y"))).isEqualTo("unit = x and y");
    }

    @Test
    void aTokenRendersWithItsPosition() {
        assertThat(new Token(Kind.WORD, "unit", 4)).hasToString("word 'unit' at 4");
    }

    @Test
    void positionsCountCharactersNotCodeUnits() {
        // A value outside the basic plane is one character to whoever typed it.
        List<Token> tokens = Lexer.tokenize("message = \"\uD83D\uDE00\" (");
        assertThat(tokens.get(2).position()).isEqualTo(13);
        QueryError caught = catchThrowableOfType(QueryError.class, () -> Lexer.tokenize("\uD83D\uDE00 $"));
        assertThat(caught.detail()).hasValue("'\uD83D\uDE00' at 0");
    }
}
