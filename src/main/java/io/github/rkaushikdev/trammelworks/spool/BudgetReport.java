package io.github.rkaushikdev.trammelworks.spool;

import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.Repr;
import java.util.Map;

/**
 * The spool's line in the nightly summary.
 *
 * @param used bytes held
 * @param limit the whole budget
 * @param reserve the bytes kept back
 * @param segments sealed segments
 * @param byClass sealed bytes per class
 */
public record BudgetReport(long used, long limit, long reserve, int segments, Map<String, Long> byClass) {

    /** Copies the breakdown. */
    public BudgetReport {
        byClass = Maps.frozen(byClass);
    }

    /** The report for a budget and what is on disk. */
    public static BudgetReport report(Budget budget, Usage usage) {
        return new BudgetReport(
                usage.used(), budget.limitBytes(), budget.reserveBytes(), usage.segments(), usage.byClass());
    }

    /** How full, as a percentage of the whole budget. */
    public double percent() {
        return limit != 0 ? 100.0 * used / limit : 0.0;
    }

    @Override
    public String toString() {
        return "spool " + used + "/" + limit + " bytes (" + Repr.fixed(percent(), 1) + "%), " + segments + " segments";
    }
}
