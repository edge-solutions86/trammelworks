package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.Stream;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * One journald export entry, with the fields it actually carried.
 *
 * <p>{@code _BOOT_ID} and {@code __MONOTONIC_TIMESTAMP} together identify a boot session and a
 * position inside it, which is the only ordering on a site controller that a drifting RTC cannot
 * corrupt.
 *
 * @param fields every field, by name
 * @param binary the fields that were read in, or must be written in, the binary form
 */
public record JournalEntry(Map<String, String> fields, Set<String> binary) {

    /** The wall clock, in microseconds since the epoch. */
    public static final String REALTIME = "__REALTIME_TIMESTAMP";
    /** Microseconds since boot. */
    public static final String MONOTONIC = "__MONOTONIC_TIMESTAMP";
    /** The boot session. */
    public static final String BOOT_ID = "_BOOT_ID";
    /** The text. */
    public static final String MESSAGE = "MESSAGE";
    /** The syslog severity. */
    public static final String PRIORITY = "PRIORITY";
    /** The container's name. */
    public static final String CONTAINER_NAME = "CONTAINER_NAME";

    /**
     * The runtime maps stdout to informational and stderr to error, so anything at error or worse
     * came from the error stream.
     */
    public static final int PRIORITY_ERROR = 3;

    /** Copies the fields. */
    public JournalEntry {
        fields = Maps.frozen(fields);
        binary = Set.copyOf(binary);
    }

    /** An entry whose fields were all text. */
    public JournalEntry(Map<String, String> fields) {
        this(fields, Set.of());
    }

    /** An empty entry. */
    public JournalEntry() {
        this(Map.of(), Set.of());
    }

    /** A field, or {@code fallback} when it is absent. */
    public String get(String name, String fallback) {
        return fields.getOrDefault(name, fallback);
    }

    /** The message, or an empty string. */
    public String message() {
        return get(MESSAGE, "");
    }

    /** The wall clock reading. */
    public NanoTime realtime() {
        String raw = fields.get(REALTIME);
        if (raw == null) {
            throw new ParseError("journal entry has no realtime timestamp");
        }
        OptionalLong micros = Coerce.integral(raw);
        if (micros.isEmpty()) {
            throw new ParseError("journal realtime is not an integer", raw);
        }
        return NanoTime.fromMicros(micros.getAsLong());
    }

    /** Microseconds since boot, when the entry says. */
    public OptionalLong monotonicUs() {
        String raw = fields.get(MONOTONIC);
        if (raw == null) {
            return OptionalLong.empty();
        }
        OptionalLong found = Coerce.integral(raw);
        if (found.isEmpty()) {
            throw new ParseError("journal monotonic is not an integer", raw);
        }
        return found;
    }

    /** The boot session, when the entry says. */
    public Optional<String> bootId() {
        return Optional.ofNullable(fields.get(BOOT_ID));
    }

    /** The container's name, when the entry says. */
    public Optional<String> unit() {
        return Optional.ofNullable(fields.get(CONTAINER_NAME));
    }

    /** Which stream the line came from, read off its priority. */
    public Stream stream() {
        String raw = fields.get(PRIORITY);
        if (raw == null) {
            return Stream.STDOUT;
        }
        OptionalLong level = Coerce.integral(raw);
        if (level.isEmpty()) {
            throw new ParseError("journal priority is not an integer", raw);
        }
        return level.getAsLong() <= PRIORITY_ERROR ? Stream.STDERR : Stream.STDOUT;
    }
}
