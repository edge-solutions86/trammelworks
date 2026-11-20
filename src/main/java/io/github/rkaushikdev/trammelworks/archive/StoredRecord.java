package io.github.rkaushikdev.trammelworks.archive;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A record as the archive holds it.
 *
 * @param key the dedupe key
 * @param site the site
 * @param unit the unit
 * @param klass the priority class it was filed under
 * @param stream the stream
 * @param observedAt what the box claimed
 * @param correctedAt what clock correction believed, when it had an opinion
 * @param confidence how far to trust the correction
 * @param message the text, already redacted
 * @param labels the labels
 * @param storedAt when the archive took it
 */
public record StoredRecord(
        String key,
        String site,
        String unit,
        String klass,
        Stream stream,
        NanoTime observedAt,
        Optional<NanoTime> correctedAt,
        String confidence,
        String message,
        Map<String, String> labels,
        NanoTime storedAt) {

    /** Copies the labels. */
    public StoredRecord {
        labels = Maps.frozen(labels);
    }

    /** The corrected time where there is one, otherwise the raw reading. */
    public NanoTime bestAt() {
        return correctedAt.orElse(observedAt);
    }

    /** Back to a live record, without byte spans: the files they pointed at are long rotated. */
    public LogRecord toRecord() {
        return new LogRecord(
                site, unit, stream, observedAt, message, List.of(), labels, false, correctedAt, confidence);
    }
}
