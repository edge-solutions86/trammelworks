package io.github.rkaushikdev.trammelworks.spool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.SpoolError;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpoolBudgetTest {

    static SegmentInfo segment(long sequence, String klass, long size) {
        return new SegmentInfo(Path.of(sequence + ".spool"), sequence, klass, size);
    }

    static Usage holding(long size) {
        return Usage.measure(List.of(segment(0, "payment", size)));
    }

    @Test
    void measuringNothingGivesZero() {
        Usage usage = Usage.measure(List.of());
        assertThat(usage.used()).isZero();
        assertThat(usage.segments()).isZero();
        assertThat(usage.byClass()).isEmpty();
    }

    @Test
    void measuringAddsTheSizesUp() {
        Usage usage = Usage.measure(List.of(segment(0, "payment", 100), segment(1, "chatter", 50)));
        assertThat(usage.used()).isEqualTo(150);
        assertThat(usage.segments()).isEqualTo(2);
    }

    @Test
    void measuringSplitsByClass() {
        Usage usage = Usage.measure(List.of(segment(0, "payment", 100), segment(1, "payment", 40)));
        assertThat(usage.ofClass("payment")).isEqualTo(140);
        assertThat(usage.ofClass("chatter")).isZero();
    }

    @Test
    void theOpenSegmentCountsTowardsTheTotal() {
        Usage usage = Usage.measure(List.of(segment(0, "payment", 100)), 25);
        assertThat(usage.used()).isEqualTo(125);
        assertThat(usage.sealedBytes()).isEqualTo(100);
        assertThat(usage.openBytes()).isEqualTo(25);
    }

    @Test
    void aNonPositiveBudgetIsRefused() {
        assertThatThrownBy(() -> new Budget(0))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("budget must be positive");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.0, 2.0})
    void aReserveOutsideTheRangeIsRefused(double ratio) {
        assertThatThrownBy(() -> new Budget(1000, ratio))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("reserve ratio must be in");
    }

    @Test
    void theReserveIsTakenOffTheTop() {
        Budget budget = new Budget(1000, 0.1);
        assertThat(budget.reserveBytes()).isEqualTo(100);
        assertThat(budget.usableBytes()).isEqualTo(900);
    }

    @Test
    void aZeroReserveMakesEverythingUsable() {
        assertThat(new Budget(1000, 0.0).usableBytes()).isEqualTo(1000);
    }

    @Test
    void freeCountsDownFromUsable() {
        assertThat(new Budget(1000, 0.1).free(holding(400))).isEqualTo(500);
    }

    @Test
    void freeNeverGoesNegative() {
        assertThat(new Budget(1000, 0.1).free(holding(5000))).isZero();
    }

    @Test
    void aWriteThatFitsIsAdmitted() {
        assertThat(new Budget(1000, 0.1).admits(holding(800), 100)).isTrue();
    }

    @Test
    void aWriteThatLandsExactlyOnUsableIsAdmitted() {
        Budget budget = new Budget(1000, 0.1);
        assertThat(budget.admits(holding(800), 100)).isTrue();
        assertThat(budget.admits(holding(900), 0)).isTrue();
    }

    @Test
    void aWriteThatWouldCrossUsableIsNotAdmitted() {
        assertThat(new Budget(1000, 0.1).admits(holding(850), 100)).isFalse();
    }

    @Test
    void aNegativeSizeIsRefused() {
        assertThatThrownBy(() -> new Budget(1000).admits(new Usage(), -1))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("negative size");
    }

    @Test
    void overByIsZeroWhenInsideTheBudget() {
        assertThat(new Budget(1000, 0.1).overBy(holding(500))).isZero();
    }

    @Test
    void overByMeasuresWhatMustBeFreed() {
        assertThat(new Budget(1000, 0.1).overBy(holding(950))).isEqualTo(50);
    }

    @Test
    void theReserveStillCoversAWritePastUsable() {
        Budget budget = new Budget(1000, 0.1);
        Usage usage = holding(900);
        assertThat(budget.admits(usage, 50)).isFalse();
        assertThat(budget.wouldExceedHardLimit(usage, 50)).isFalse();
    }

    @Test
    void aWritePastTheHardLimitIsNamed() {
        assertThat(new Budget(1000, 0.1).wouldExceedHardLimit(holding(900), 200))
                .isTrue();
    }

    @Test
    void theFillRatioIsMeasuredAgainstTheWholeBudget() {
        assertThat(new Budget(1000, 0.1).fillRatio(holding(500))).isEqualTo(0.5);
    }

    @Test
    void theReportCarriesTheNumbers() {
        BudgetReport built = BudgetReport.report(new Budget(1000, 0.1), holding(250));
        assertThat(built.used()).isEqualTo(250);
        assertThat(built.limit()).isEqualTo(1000);
        assertThat(built.reserve()).isEqualTo(100);
        assertThat(built.segments()).isEqualTo(1);
    }

    @Test
    void theReportPercentageIsOfTheBudget() {
        assertThat(BudgetReport.report(new Budget(1000, 0.1), holding(250)).percent())
                .isEqualTo(25.0);
    }

    @Test
    void theReportReadsAsOneLine() {
        assertThat(BudgetReport.report(new Budget(1000, 0.1), holding(250)).toString())
                .isEqualTo("spool 250/1000 bytes (25.0%), 1 segments");
    }

    @Test
    void theReportKeepsTheClassBreakdown() {
        BudgetReport built = BudgetReport.report(
                new Budget(1000), Usage.measure(List.of(segment(0, "payment", 100), segment(1, "chatter", 40))));
        assertThat(built.byClass()).isEqualTo(Map.of("payment", 100L, "chatter", 40L));
    }
}
