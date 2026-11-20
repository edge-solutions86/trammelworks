package io.github.rkaushikdev.trammelworks;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One reassembled application line, ready for the rest of the pipeline.
 *
 * <p>A record carries two times once it has been through clock correction: {@code observedAt} is
 * what the box claimed and never changes, and {@code correctedAt} is what we believe. A dispute
 * review compares our record against the till roll, and "the box said 11:29:53 and we think it was
 * really 11:30:00" is an answer, while a single silently adjusted timestamp is not.
 *
 * @param site the estate's site id
 * @param unit the container's service name
 * @param stream the descriptor it wrote to
 * @param observedAt what the box claimed the time was
 * @param message the reassembled line
 * @param sources every fragment's location, in order
 * @param labels extra facts, such as the boot id
 * @param lossy whether any fragment had undecodable bytes
 * @param correctedAt what clock correction believes the time was, when it has an opinion
 * @param clockConfidence how far to trust {@code correctedAt}
 */
public record LogRecord(
        String site,
        String unit,
        Stream stream,
        NanoTime observedAt,
        String message,
        List<SourceRef> sources,
        Map<String, String> labels,
        boolean lossy,
        Optional<NanoTime> correctedAt,
        String clockConfidence) {

    /** Refuses an empty site or unit, and copies the collections. */
    public LogRecord {
        if (site == null || site.isEmpty()) {
            throw new IllegalArgumentException("site must not be empty");
        }
        if (unit == null || unit.isEmpty()) {
            throw new IllegalArgumentException("unit must not be empty");
        }
        sources = List.copyOf(sources);
        labels = Maps.frozen(labels);
    }

    /** A record with no sources, labels or correction. */
    public LogRecord(String site, String unit, Stream stream, NanoTime observedAt, String message) {
        this(site, unit, stream, observedAt, message, List.of(), Map.of(), false, Optional.empty(), "unknown");
    }

    /** For anything that has to talk to a calendar. Loses three digits. */
    public OffsetDateTime toDateTime() {
        return observedAt.toDateTime();
    }

    /** Length of the message in UTF-8, which is what the meter counts. */
    public int byteLength() {
        return message.getBytes(StandardCharsets.UTF_8).length;
    }

    /** How many physical lines this record was joined from. */
    public int fragmentCount() {
        return sources.size();
    }

    /** The corrected time where there is one, otherwise the raw reading. */
    public NanoTime bestAt() {
        return correctedAt.orElse(observedAt);
    }

    /** The same record with extra labels merged over the existing ones. */
    public LogRecord withLabels(Map<String, String> extra) {
        Map<String, String> merged = new LinkedHashMap<>(labels);
        merged.putAll(extra);
        return new LogRecord(
                site, unit, stream, observedAt, message, sources, merged, lossy, correctedAt, clockConfidence);
    }

    /** The same record with one extra label. */
    public LogRecord withLabel(String key, String value) {
        return withLabels(Map.of(key, value));
    }

    /** The same record with its labels replaced outright. */
    public LogRecord replaceLabels(Map<String, String> replacement) {
        return new LogRecord(
                site, unit, stream, observedAt, message, sources, replacement, lossy, correctedAt, clockConfidence);
    }

    /** The same record with different text. */
    public LogRecord withMessage(String text) {
        return new LogRecord(
                site, unit, stream, observedAt, text, sources, labels, lossy, correctedAt, clockConfidence);
    }

    /** The same record from a different site. */
    public LogRecord withSite(String value) {
        return new LogRecord(
                value, unit, stream, observedAt, message, sources, labels, lossy, correctedAt, clockConfidence);
    }

    /** The same record from a different unit. */
    public LogRecord withUnit(String value) {
        return new LogRecord(
                site, value, stream, observedAt, message, sources, labels, lossy, correctedAt, clockConfidence);
    }

    /** The same record on a different stream. */
    public LogRecord withStream(Stream value) {
        return new LogRecord(
                site, unit, value, observedAt, message, sources, labels, lossy, correctedAt, clockConfidence);
    }

    /** The same record observed at a different time. */
    public LogRecord withObservedAt(NanoTime value) {
        return new LogRecord(site, unit, stream, value, message, sources, labels, lossy, correctedAt, clockConfidence);
    }

    /** The same record joined from different sources. */
    public LogRecord withSources(List<SourceRef> value) {
        return new LogRecord(
                site, unit, stream, observedAt, message, value, labels, lossy, correctedAt, clockConfidence);
    }

    /** The same record, lossy or not. */
    public LogRecord withLossy(boolean value) {
        return new LogRecord(
                site, unit, stream, observedAt, message, sources, labels, value, correctedAt, clockConfidence);
    }

    /** The same record with a corrected time and the confidence that goes with it. */
    public LogRecord withCorrection(Optional<NanoTime> value, String confidence) {
        return new LogRecord(site, unit, stream, observedAt, message, sources, labels, lossy, value, confidence);
    }

    /** The same record with a corrected time, keeping the confidence. */
    public LogRecord withCorrectedAt(NanoTime value) {
        return withCorrection(Optional.of(value), clockConfidence);
    }

    /** The same record with a different confidence, keeping the corrected time. */
    public LogRecord withClockConfidence(String value) {
        return withCorrection(correctedAt, value);
    }

    /** The record as the nightly summary and the batch writer see it. */
    public Map<String, Object> asDict() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site", site);
        payload.put("unit", unit);
        payload.put("stream", stream.value());
        payload.put("observed_at", observedAt.isoformat());
        payload.put("message", message);
        payload.put("labels", new LinkedHashMap<>(labels));
        List<String> refs = new ArrayList<>();
        for (SourceRef ref : sources) {
            refs.add(ref.toString());
        }
        payload.put("sources", refs);
        payload.put("clock_confidence", clockConfidence);
        correctedAt.ifPresent(value -> payload.put("corrected_at", value.isoformat()));
        return payload;
    }
}
