package io.github.rkaushikdev.trammelworks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** What a whole directory of batches turned out to be. */
public final class ReplayReport {

    private final List<BatchVerdict> verdicts = new ArrayList<>();

    /** An empty report. */
    public ReplayReport() {}

    /** Every verdict, by batch id and then file name. */
    public List<BatchVerdict> verdicts() {
        return Collections.unmodifiableList(verdicts);
    }

    /** The batches that may be taken as genuine. */
    public List<BatchVerdict> accepted() {
        return verdicts.stream().filter(BatchVerdict::ok).toList();
    }

    /** The batches that may not. */
    public List<BatchVerdict> refused() {
        return verdicts.stream().filter(item -> !item.ok()).toList();
    }

    /** Records across the accepted batches. */
    public int records() {
        return accepted().stream().mapToInt(BatchVerdict::records).sum();
    }

    /** The sites the accepted batches came from, sorted. */
    public List<String> sites() {
        TreeSet<String> found = new TreeSet<>(Json::compareCodePoints);
        for (BatchVerdict item : accepted()) {
            if (!item.site().isEmpty()) {
                found.add(item.site());
            }
        }
        return List.copyOf(found);
    }

    /** Whether there was something to check and all of it passed. */
    public boolean clean() {
        return !verdicts.isEmpty() && refused().isEmpty();
    }

    void add(BatchVerdict verdict) {
        verdicts.add(verdict);
    }

    void sort() {
        verdicts.sort((left, right) -> {
            int order = Json.compareCodePoints(left.batchId(), right.batchId());
            return order != 0
                    ? order
                    : Json.compareCodePoints(
                            left.path().getFileName().toString(),
                            right.path().getFileName().toString());
        });
    }

    @Override
    public String toString() {
        String end = refused().isEmpty() ? "" : ", " + refused().size() + " refused";
        return accepted().size() + " batches accepted, " + records() + " records from " + sites().size() + " sites"
                + end;
    }
}
