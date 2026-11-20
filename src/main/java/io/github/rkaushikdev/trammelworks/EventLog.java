package io.github.rkaushikdev.trammelworks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Our own diagnostic output: JSON lines with a fixed envelope.
 *
 * <p>An aggregator that writes unstructured chatter into the directory it reads from will
 * eventually ingest itself, so this writes JSON lines to a separate sink and never to the
 * container log root. The estate parses it, which means the field set is fixed: {@code at},
 * {@code level} and {@code event} are the envelope and no field may shadow them.
 *
 * <p>Fields are passed as alternating names and values. Every line written is also kept, so a test
 * does not need a file.
 */
public final class EventLog {

    /** The envelope's field names. */
    public static final Set<String> RESERVED_KEYS = Set.of("at", "level", "event");

    private static final Json.Style LINE =
            Json.Style.plain().withSeparators(",", ":").withFallback(String::valueOf);

    private final EventSink sink;
    private final Level minimum;
    private final Clock clock;
    private final Map<String, String> context;
    private final List<String> lines;

    /** A log keeping lines at {@code minimum} and above, writing to no sink. */
    public EventLog(Level minimum, Clock clock) {
        this(null, minimum, clock);
    }

    /** A log writing to {@code sink}, which may be {@code null}. */
    public EventLog(EventSink sink, Level minimum, Clock clock) {
        this(sink, minimum, clock, Map.of(), new ArrayList<>());
    }

    private EventLog(EventSink sink, Level minimum, Clock clock, Map<String, String> context, List<String> lines) {
        this.sink = sink;
        this.minimum = minimum;
        this.clock = clock;
        this.context = Maps.frozen(context);
        this.lines = lines;
    }

    /** A log that keeps nothing below error, for code paths that must not be chatty. */
    public static EventLog nullLog() {
        return new EventLog(null, Level.ERROR, new SystemClock());
    }

    /** Adapt a plain callable into a sink. */
    public static EventSink countingSink(Consumer<String> target) {
        return EventSink.of(target);
    }

    /** The lowest level this log keeps. */
    public Level minimum() {
        return minimum;
    }

    /** The clock stamping each event. */
    public Clock clock() {
        return clock;
    }

    /** A child log that stamps extra fields onto every event and shares this log's buffer. */
    public EventLog bind(String... pairs) {
        Map<String, String> merged = new LinkedHashMap<>(context);
        Map<String, Object> extra = Maps.of((Object[]) pairs);
        extra.forEach((key, value) -> merged.put(key, (String) value));
        return new EventLog(sink, minimum, clock, merged, lines);
    }

    /** Write one event, if its level is at or above the minimum. */
    public void emit(Level level, String event, Object... fields) {
        if (level.severity() < minimum.severity()) {
            return;
        }
        Map<String, Object> extra = Maps.of(fields);
        Set<String> clash = new TreeSet<>(extra.keySet());
        clash.retainAll(RESERVED_KEYS);
        if (!clash.isEmpty()) {
            throw new StanchionError(
                    "event field shadows the envelope: " + clash.iterator().next());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("at", clock.now().isoformat());
        payload.put("level", level.name());
        payload.put("event", event);
        payload.putAll(context);
        payload.putAll(extra);
        String line = Json.dumps(payload, LINE);
        lines.add(line);
        if (sink != null) {
            sink.write(line + "\n");
        }
    }

    /** An event at debug. */
    public void debug(String event, Object... fields) {
        emit(Level.DEBUG, event, fields);
    }

    /** An event at info. */
    public void info(String event, Object... fields) {
        emit(Level.INFO, event, fields);
    }

    /** An event at warn. */
    public void warn(String event, Object... fields) {
        emit(Level.WARN, event, fields);
    }

    /** An event at error. */
    public void error(String event, Object... fields) {
        emit(Level.ERROR, event, fields);
    }

    /**
     * A failure at error, with the stable code where there is one. Anything else is labelled
     * {@code java.<SimpleName>}.
     */
    public void exception(String event, Throwable exc, Object... fields) {
        Object[] envelope;
        if (exc instanceof StanchionError known) {
            envelope = new Object[] {"code", known.code(), "message", known.message()};
        } else {
            envelope = new Object[] {
                "code", "java." + exc.getClass().getSimpleName(), "message", Objects.toString(exc.getMessage(), "")
            };
        }
        Object[] merged = new Object[envelope.length + fields.length];
        System.arraycopy(envelope, 0, merged, 0, envelope.length);
        System.arraycopy(fields, 0, merged, envelope.length, fields.length);
        error(event, merged);
    }

    /** Everything written, as a copy. */
    public List<String> lines() {
        return List.copyOf(lines);
    }

    /** Everything written, decoded. */
    public List<Map<String, Object>> records() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : lines) {
            out.add(Json.loadsObject(line));
        }
        return out;
    }
}
