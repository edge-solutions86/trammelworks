package io.github.rkaushikdev.trammelworks.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.QueryError;
import org.junit.jupiter.api.Test;

class QueryPlanTest {

    static final NanoTime NOW = NanoTime.parse("2025-03-04T12:00:00Z");

    static Plan planned(String text) {
        return Plan.plan(Parser.parseQuery(text, NOW));
    }

    /** A node no consumer knows how to read. */
    record Odd() implements Node {
        @Override
        public String render() {
            return "odd";
        }
    }

    @Test
    void anEmptyFilterPlansToEverything() {
        Plan found = planned("");
        assertThat(found.whereSql()).isEqualTo("1");
        assertThat(found.params()).isEmpty();
        assertThat(found.fullyPushed()).isTrue();
    }

    @Test
    void anEqualityBecomesABoundParameter() {
        Plan found = planned("unit = payment-kernel");
        assertThat(found.whereSql()).isEqualTo("unit = ?");
        assertThat(found.params()).containsExactly("payment-kernel");
    }

    @Test
    void theClassFieldMapsToItsColumn() {
        assertThat(planned("class = payment").whereSql()).isEqualTo("klass = ?");
    }

    @Test
    void aValueNeverReachesTheSqlText() {
        Plan found = planned("unit = \"x; DROP TABLE records; --\"");
        assertThat(found.whereSql()).doesNotContain("DROP").isEqualTo("unit = ?");
        assertThat(found.params()).containsExactly("x; DROP TABLE records; --");
    }

    @Test
    void aContainsBecomesALike() {
        Plan found = planned("message ~ declined");
        assertThat(found.whereSql()).contains("LIKE ?");
        assertThat(found.params()).containsExactly("%declined%");
    }

    @Test
    void aNegatedContainsBecomesANotLike() {
        assertThat(planned("message !~ heartbeat").whereSql()).contains("NOT LIKE ?");
    }

    @Test
    void likeWildcardsInTheValueAreEscaped() {
        Plan found = planned("message ~ '100%_done'");
        assertThat(found.params()).containsExactly("%100\\%\\_done%");
        assertThat(found.whereSql()).contains("ESCAPE");
    }

    @Test
    void anInListBecomesPlaceholders() {
        Plan found = planned("unit in (a, b, c)");
        assertThat(found.whereSql()).isEqualTo("unit IN (?, ?, ?)");
        assertThat(found.params()).containsExactly("a", "b", "c");
    }

    @Test
    void aSinceWindowComparesTheCorrectedTime() {
        Plan found = planned("since 2h");
        assertThat(found.whereSql()).contains("COALESCE(corrected_ns, observed_ns) >= ?");
        assertThat(found.params())
                .containsExactly(NanoTime.parse("2025-03-04T10:00:00Z").epochNs());
    }

    @Test
    void anUntilWindowComparesTheOtherWay() {
        assertThat(planned("until 2h").whereSql()).contains("<= ?");
    }

    @Test
    void anAndJoinsWithAnd() {
        Plan found = planned("unit = a and stream = stderr");
        assertThat(found.whereSql()).isEqualTo("(unit = ? AND stream = ?)");
        assertThat(found.params()).containsExactly("a", "stderr");
    }

    @Test
    void anOrJoinsWithOr() {
        assertThat(planned("unit = a or unit = b").whereSql()).isEqualTo("(unit = ? OR unit = ?)");
    }

    @Test
    void aNotBecomesASqlNot() {
        assertThat(planned("not unit = a").whereSql()).isEqualTo("(NOT unit = ?)");
    }

    @Test
    void theParametersStayInTreeOrder() {
        assertThat(planned("unit = a and (stream = b or class = c)").params()).containsExactly("a", "b", "c");
    }

    @Test
    void aPlainFilterIsFullyPushed() {
        assertThat(planned("unit = a and since 2h").fullyPushed()).isTrue();
    }

    @Test
    void aLabelPredicateIsNotPushable() {
        assertThat(Plan.isPushable(new Compare("label.lane", Op.EQ, "3"))).isFalse();
    }

    @Test
    void aLabelAloneLeavesEverythingToTheEvaluator() {
        Plan found = planned("label.lane = 3");
        assertThat(found.whereSql()).isEqualTo("1");
        assertThat(found.needsPostFilter()).isTrue();
    }

    @Test
    void aLabelUnderAnAndPushesTheOtherSide() {
        Plan found = planned("unit = payment-kernel and label.lane = 3");
        assertThat(found.whereSql()).isEqualTo("unit = ?");
        assertThat(found.params()).containsExactly("payment-kernel");
        assertThat(found.needsPostFilter()).isTrue();
    }

    @Test
    void aLabelUnderAnOrPushesNothing() {
        // Pushing the non label half of an or would drop rows that match only
        // through the half that was not pushed.
        Plan found = planned("unit = payment-kernel or label.lane = 3");
        assertThat(found.whereSql()).isEqualTo("1");
        assertThat(found.needsPostFilter()).isTrue();
    }

    @Test
    void aLabelUnderANotPushesNothing() {
        assertThat(planned("not label.lane = 3").whereSql()).isEqualTo("1");
    }

    @Test
    void twoLabelsUnderAnAndBothStayBehind() {
        Plan found = planned("label.lane = 3 and label.pump = 7");
        assertThat(found.whereSql()).isEqualTo("1");
        assertThat(found.needsPostFilter()).isTrue();
    }

    @Test
    void aNestedAndPushesWhatItCan() {
        Plan found = planned("unit = a and stream = stderr and label.lane = 3");
        assertThat(found.params()).containsExactly("a", "stderr");
        assertThat(found.needsPostFilter()).isTrue();
    }

    @Test
    void theStatementOrdersByTheCorrectedTime() {
        assertThat(planned("").sql()).contains("ORDER BY COALESCE(corrected_ns, observed_ns) ASC");
    }

    @Test
    void theStatementCanSelectANarrowerColumnList() {
        assertThat(planned("").sql("id, klass")).startsWith("SELECT id, klass FROM records");
    }

    @Test
    void aLimitIsPushedWhenNothingIsLeftOver() {
        assertThat(planned("unit = a limit 10").sql()).endsWith("LIMIT 10");
    }

    @Test
    void aLimitIsNotPushedWhenAPostFilterRemains() {
        // The post filter would be handed ten rows and might keep two, which is not
        // what "limit 10" was asked for.
        assertThat(planned("label.lane = 3 limit 10").sql()).doesNotContain("LIMIT");
    }

    @Test
    void aQueryWithNoLimitHasNoLimitClause() {
        assertThat(planned("unit = a").sql()).doesNotContain("LIMIT");
    }

    @Test
    void anUnplannableNodeIsRefused() {
        assertThatThrownBy(() -> Plan.plan(Query.of(new Odd())))
                .isInstanceOf(QueryError.class)
                .hasMessageContaining("cannot be planned");
    }
}
