package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Bytes;
import io.github.rkaushikdev.trammelworks.Fragment;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.SourceRef;
import io.github.rkaushikdev.trammelworks.Stream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Puts the lines the runtime split back together, for one container.
 *
 * <p>stdout and stderr interleave, so each stream has its own pending buffer: joining across them
 * produces a line that never existed. The first chunk owns the timestamp and the labels, because it
 * was there when the event happened. A line has a cap, and is cut at it on a character boundary. And
 * the end of a file is not the end of a line, so emitting what is still pending is a separate call
 * made only when nothing more can arrive.
 */
public final class Joiner {

    /** The label a cut or unfinished record carries. */
    public static final String TRUNCATED_LABEL = "truncated";

    /** The label a record with replaced bytes carries. */
    public static final String LOSSY_LABEL = "lossy";

    private final String site;
    private final String unit;
    private final JoinLimits limits;
    private final JoinStats stats = new JoinStats();
    private final Map<Stream, Pending> pending = new LinkedHashMap<>();

    private static final class Pending {
        final Fragment first;
        final List<String> parts = new ArrayList<>();
        final List<SourceRef> sources = new ArrayList<>();
        long size;
        boolean lossy;
        boolean truncated;
        boolean dropping;

        Pending(Fragment first) {
            this.first = first;
        }
    }

    /** A joiner with the default limits. */
    public Joiner(String site, String unit) {
        this(site, unit, new JoinLimits());
    }

    /** A joiner with the given limits. */
    public Joiner(String site, String unit, JoinLimits limits) {
        this.site = site;
        this.unit = unit;
        this.limits = limits;
    }

    /** The running numbers. */
    public JoinStats stats() {
        return stats;
    }

    /** Take one physical line. Returns a record when one is complete. */
    public Optional<LogRecord> feed(Fragment fragment) {
        stats.fragment();
        Pending held = pending.get(fragment.stream());
        if (held == null) {
            if (fragment.complete()) {
                stats.record();
                return Optional.of(single(fragment));
            }
            Pending opened = new Pending(fragment);
            append(opened, fragment);
            pending.put(fragment.stream(), opened);
            return Optional.empty();
        }
        append(held, fragment);
        if (fragment.partial()) {
            return Optional.empty();
        }
        pending.remove(fragment.stream());
        stats.record();
        stats.join();
        if (held.truncated) {
            stats.truncate();
        }
        return Optional.of(assemble(held));
    }

    /** Emit what every stream still holds, because the file has finished. */
    public List<LogRecord> flush() {
        return flushStreams(new ArrayList<>(pending.keySet()));
    }

    /** Emit what one stream still holds. */
    public List<LogRecord> flush(Stream stream) {
        return flushStreams(List.of(stream));
    }

    private List<LogRecord> flushStreams(List<Stream> targets) {
        List<LogRecord> out = new ArrayList<>();
        for (Stream target : targets) {
            Pending held = pending.remove(target);
            if (held == null) {
                continue;
            }
            held.truncated = true;
            stats.record();
            stats.join();
            stats.truncate();
            stats.flushOpen();
            out.add(assemble(held));
        }
        return out;
    }

    /** The streams with a line still open, in the order they opened. */
    public List<Stream> pendingStreams() {
        return List.copyOf(pending.keySet());
    }

    private void append(Pending held, Fragment fragment) {
        held.lossy = held.lossy || fragment.lossy();
        if (held.dropping) {
            stats.drop();
            return;
        }
        if (held.parts.size() >= limits.maxChunks()) {
            held.truncated = true;
            held.dropping = true;
            stats.drop();
            return;
        }
        long room = limits.maxLineBytes() - held.size;
        String chunk = fragment.message();
        long size = chunk.getBytes(StandardCharsets.UTF_8).length;
        if (size > room) {
            // The byte cap can land inside this chunk, so keep the part that fits. The chunk cap
            // above cannot, so it keeps nothing.
            held.truncated = true;
            held.dropping = true;
            String kept = room > 0 ? cutToBytes(chunk, (int) room) : "";
            if (!kept.isEmpty()) {
                held.parts.add(kept);
                held.sources.add(fragment.source());
                held.size += kept.getBytes(StandardCharsets.UTF_8).length;
            }
            stats.drop();
            return;
        }
        held.parts.add(chunk);
        held.sources.add(fragment.source());
        held.size += size;
    }

    private LogRecord single(Fragment fragment) {
        Map<String, String> labels = new LinkedHashMap<>(fragment.labels());
        if (fragment.lossy()) {
            labels.put(LOSSY_LABEL, "true");
        }
        return new LogRecord(site, unit, fragment.stream(), fragment.observedAt(), fragment.message())
                .withSources(List.of(fragment.source()))
                .replaceLabels(labels)
                .withLossy(fragment.lossy());
    }

    private LogRecord assemble(Pending held) {
        Map<String, String> labels = new LinkedHashMap<>(held.first.labels());
        if (held.truncated) {
            labels.put(TRUNCATED_LABEL, "true");
        }
        if (held.lossy) {
            labels.put(LOSSY_LABEL, "true");
        }
        return new LogRecord(site, unit, held.first.stream(), held.first.observedAt(), String.join("", held.parts))
                .withSources(held.sources)
                .replaceLabels(labels)
                .withLossy(held.lossy);
    }

    /** Cut a string to at most {@code limit} UTF-8 bytes without splitting a character. */
    static String cutToBytes(String text, int limit) {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= limit) {
            return text;
        }
        return Bytes.decodeIgnoring(Arrays.copyOf(encoded, limit));
    }
}
