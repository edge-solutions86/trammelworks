package io.github.rkaushikdev.trammelworks.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.QueryError;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueryParserTest {

    static final NanoTime NOW = NanoTime.parse("2025-03-04T12:00:00Z");

    static Query parse(String text) {
        return Parser.parseQuery(text, NOW);
    }

    static Node where(String text) {
        return parse(text).where().orElseThrow();
    }

    static long ns(String raw) {
        return NanoTime.parse(raw).epochNs();
    }

    @Test
    void anEmptyFilterMatchesEverything() {
        Query found = parse("");
        assertThat(found.matchesEverything()).isTrue();
        assertThat(found.render()).isEqualTo("everything");
    }

    @ParameterizedTest
    @ValueSource(strings = {"site", "unit", "class", "stream", "message", "confidence"})
    void everyKnownFieldIsAccepted(String field) {
        assertThat(Ast.checkField(field)).isEqualTo(field);
    }

    @Test
    void aLabelFieldIsAccepted() {
        assertThat(Ast.checkField("label.lane")).isEqualTo("label.lane");
    }

    @Test
    void aLabelWithNoNameIsRefused() {
        assertThatThrownBy(() -> Ast.checkField("label."))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("a label needs a name");
    }

    @Test
    void anUnknownFieldIsRefused() {
        assertThatThrownBy(() -> Ast.checkField("unti"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("unknown field");
    }

    @Test
    void aFieldNameIsLowercased() {
        assertThat(Ast.checkField("UNIT")).isEqualTo("unit");
    }

    @Test
    void aSimpleComparisonParses() {
        Compare found = (Compare) where("unit = payment-kernel");
        assertThat(found.field()).isEqualTo("unit");
        assertThat(found.op()).isEqualTo(Op.EQ);
        assertThat(found.value()).isEqualTo("payment-kernel");
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "!=", "~", "!~", "<", "<=", ">", ">="})
    void everyOperatorParses(String operator) {
        assertThat(where("unit " + operator + " x"))
                .isInstanceOfSatisfying(
                        Compare.class, found -> assertThat(found.op()).isEqualTo(Op.of(operator)));
    }

    @Test
    void aQuotedValueKeepsItsSpaces() {
        assertThat(((Compare) where("message ~ \"auth declined\"")).value()).isEqualTo("auth declined");
    }

    @Test
    void anAndParsesAsATree() {
        assertThat(where("unit = a and stream = stderr")).isInstanceOf(And.class);
    }

    @Test
    void anOrParsesAsATree() {
        assertThat(where("unit = a or unit = b")).isInstanceOf(Or.class);
    }

    @Test
    void andBindsTighterThanOr() {
        Or found = (Or) where("unit = a or unit = b and stream = stderr");
        assertThat(found.right()).isInstanceOf(And.class);
    }

    @Test
    void parenthesesOverrideThePrecedence() {
        And found = (And) where("(unit = a or unit = b) and stream = stderr");
        assertThat(found.left()).isInstanceOf(Or.class);
    }

    @Test
    void anUnclosedParenthesisIsRefused() {
        assertThatThrownBy(() -> parse("(unit = a"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("closing parenthesis");
    }

    @Test
    void notNegatesOneTerm() {
        Not found = (Not) where("not unit = a");
        assertThat(found.inner()).isInstanceOf(Compare.class);
    }

    @Test
    void notBindsTighterThanAnd() {
        And found = (And) where("not unit = a and stream = stderr");
        assertThat(found.left()).isInstanceOf(Not.class);
    }

    @Test
    void twoAdjacentTermsAreJoinedByAnImpliedAnd() {
        assertThat(where("unit = a stream = stderr")).isInstanceOf(And.class);
    }

    @Test
    void anInListParses() {
        assertThat(((InSet) where("unit in (pump-bridge, tank-gauge)")).values())
                .containsExactly("pump-bridge", "tank-gauge");
    }

    @Test
    void anInListOfOneIsAllowed() {
        assertThat(((InSet) where("unit in (pump-bridge)")).values()).containsExactly("pump-bridge");
    }

    @Test
    void anEmptyInListIsRefused() {
        assertThatThrownBy(() -> parse("unit in ()"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("expected a value");
    }

    @Test
    void sinceReadsADurationBackFromNow() {
        assertThat(((Since) where("since 2h")).atNs()).isEqualTo(ns("2025-03-04T10:00:00Z"));
    }

    @Test
    void sinceReadsAnAbsoluteTimestamp() {
        assertThat(((Since) where("since 2025-03-04T09:30:00Z")).atNs()).isEqualTo(ns("2025-03-04T09:30:00Z"));
    }

    @Test
    void untilReadsADuration() {
        assertThat(((Until) where("until 30m")).atNs()).isEqualTo(ns("2025-03-04T11:30:00Z"));
    }

    @Test
    void aWindowPairsWithACondition() {
        assertThat(where("since 2h and unit = payment-kernel")).isInstanceOf(And.class);
    }

    @Test
    void anUnreadableTimeIsRefused() {
        assertThatThrownBy(() -> parse("since yesterday"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("not a time or a duration");
    }

    @Test
    void aLimitIsRead() {
        assertThat(parse("unit = a limit 40").limit()).hasValue(40);
    }

    @Test
    void aLimitAloneIsAllowed() {
        Query found = parse("limit 5");
        assertThat(found.limit()).hasValue(5);
        assertThat(found.matchesEverything()).isTrue();
    }

    @Test
    void aLimitOfZeroIsRefused() {
        assertThatThrownBy(() -> parse("limit 0"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void aNonNumericLimitIsRefused() {
        assertThatThrownBy(() -> parse("limit lots"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("whole number");
    }

    @Test
    void aMissingOperatorIsRefused() {
        assertThatThrownBy(() -> parse("unit payment-kernel x"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("comparison operator");
    }

    @Test
    void aMissingValueIsRefused() {
        assertThatThrownBy(() -> parse("unit ="))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("expected a value");
    }

    @Test
    void aKeywordWhereAFieldBelongsIsRefused() {
        assertThatThrownBy(() -> parse("and unit = a"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("expected a field name");
    }

    @Test
    void trailingInputIsRefused() {
        assertThatThrownBy(() -> parse("unit = a )"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("trailing input");
    }

    @Test
    void theErrorNamesTheTokenAndItsPosition() {
        QueryError caught = catchThrowableOfType(QueryError.class, () -> parse("unit = a )"));
        assertThat(caught.detail())
                .hasValueSatisfying(detail -> assertThat(detail).contains("at 9"));
    }

    @Test
    void aFilterRendersBack() {
        assertThat(parse("unit = payment-kernel").render()).isEqualTo("unit = payment-kernel");
    }

    @Test
    void aValueWithSpacesRendersQuoted() {
        assertThat(parse("message ~ \"auth declined\"").render()).isEqualTo("message ~ \"auth declined\"");
    }

    @Test
    void aTreeRendersWithItsParentheses() {
        assertThat(parse("unit = a and stream = stderr").render()).isEqualTo("(unit = a and stream = stderr)");
    }

    @Test
    void aRenderedFilterParsesAgain() {
        Query once = parse("unit in (a, b) and not message ~ heartbeat limit 10");
        assertThat(parse(once.render()).render()).isEqualTo(once.render());
    }

    @Test
    void theFieldsUsedAreReported() {
        assertThat(Ast.fieldsUsed(where("unit = a and stream = stderr"))).isEqualTo(Set.of("unit", "stream"));
    }

    @Test
    void aWindowUsesNoFields() {
        assertThat(Ast.fieldsUsed(where("since 2h"))).isEmpty();
    }

    @Test
    void anEmptyFilterUsesNoFields() {
        assertThat(Ast.fieldsUsed(null)).isEmpty();
    }

    @Test
    void parseInstantReadsBothForms() {
        assertThat(Parser.parseInstant("2h", NOW)).isEqualTo(ns("2025-03-04T10:00:00Z"));
        assertThat(Parser.parseInstant("2025-01-01T00:00:00Z", NOW)).isEqualTo(ns("2025-01-01T00:00:00Z"));
    }

    @Test
    void notInParsesAsANegatedSet() {
        assertThat(where("unit not in (a, b)")).isEqualTo(new InSet("unit", List.of("a", "b"), true));
    }

    @Test
    void notInRendersTheWayItWasTyped() {
        assertThat(where("unit not in (a, b)").render()).isEqualTo("unit not in (a, b)");
    }

    @Test
    void aPlainInIsNotNegated() {
        assertThat(((InSet) where("unit in (a)")).negated()).isFalse();
    }

    @Test
    void notWithoutInAfterAFieldIsRefused() {
        assertThatThrownBy(() -> parse("unit not = a"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("expected in after not");
    }

    @Test
    void aLeadingNotStillNegatesAWholeTerm() {
        assertThat(where("not unit in (a)")).isInstanceOf(Not.class);
    }

    @Test
    void aLimitTooLargeForTheArchiveIsRefused() {
        assertThatThrownBy(() -> parse("limit 99999999999"))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("limit is too large");
    }
}
