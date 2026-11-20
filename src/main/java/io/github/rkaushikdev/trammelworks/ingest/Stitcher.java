package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.SourceRef;
import io.github.rkaushikdev.trammelworks.Stream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Applies one multiline rule to the records of one unit, each stream held separately. */
public final class Stitcher {

    /** What the lines of a stitched event are joined with. */
    public static final String JOIN_WITH = "\n";

    /** The label carrying how many lines a stitched record holds. */
    public static final String STITCHED_LABEL = "stitched";

    private final MultilineRule rule;
    private final Map<Stream, Held> held = new LinkedHashMap<>();

    private static final class Held {
        final LogRecord head;
        final List<String> lines = new ArrayList<>();
        final List<SourceRef> sources = new ArrayList<>();
        boolean truncated;

        Held(LogRecord head) {
            this.head = head;
            lines.add(head.message());
            sources.addAll(head.sources());
        }
    }

    /** A stitcher for one rule. */
    public Stitcher(MultilineRule rule) {
        this.rule = rule;
    }

    /** The rule applied. */
    public MultilineRule rule() {
        return rule;
    }

    /** Take one record. Returns whatever became complete because of it. */
    public List<LogRecord> feed(LogRecord record) {
        Held current = held.get(record.stream());
        if (current == null) {
            held.put(record.stream(), new Held(record));
            return List.of();
        }
        if (!rule.continues(record.message())) {
            List<LogRecord> out = List.of(assemble(current));
            held.put(record.stream(), new Held(record));
            return out;
        }
        if (current.lines.size() >= rule.maxLines()) {
            current.truncated = true;
            return List.of();
        }
        current.lines.add(record.message());
        current.sources.addAll(record.sources());
        return List.of();
    }

    /** Emit everything still held, oldest stream first. */
    public List<LogRecord> flush() {
        List<LogRecord> out = new ArrayList<>();
        for (Held current : held.values()) {
            out.add(assemble(current));
        }
        held.clear();
        return out;
    }

    /** The streams with an event still held, in the order they opened. */
    public List<Stream> heldStreams() {
        return List.copyOf(held.keySet());
    }

    private static LogRecord assemble(Held current) {
        if (current.lines.size() == 1 && !current.truncated) {
            return current.head;
        }
        Map<String, String> labels = new LinkedHashMap<>(current.head.labels());
        labels.put(STITCHED_LABEL, Integer.toString(current.lines.size()));
        if (current.truncated) {
            labels.put("truncated", "true");
        }
        return current.head
                .withMessage(String.join(JOIN_WITH, current.lines))
                .withSources(current.sources)
                .replaceLabels(labels);
    }
}
