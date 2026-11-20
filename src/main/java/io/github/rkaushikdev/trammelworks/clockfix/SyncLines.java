package io.github.rkaushikdev.trammelworks.clockfix;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spotting a clock correction in the log stream itself.
 *
 * <p>There is no API on a site controller that says "the clock was just fixed"; the only evidence
 * is the line the time daemon writes. chronyd logs {@code System clock wrong by -1.234567 seconds},
 * where a <b>negative</b> value means the clock was behind (getting that backwards doubles the
 * error instead of removing it). systemd-timesyncd logs {@code Initial synchronization to time
 * server ...} with no number, which still says the clock is right from now on. A record with no
 * monotonic label cannot produce a sync: guessing one would put a measured correction at an
 * estimated place.
 */
public final class SyncLines {

    /** The label carrying a record's position in its boot session. */
    public static final String MONOTONIC_LABEL = "monotonic_ns";

    /** The label carrying a record's boot session. */
    public static final String BOOT_LABEL = "boot_id";

    /** The unit names chronyd runs under. */
    public static final Set<String> CHRONY_UNITS = Set.of("chronyd", "chrony");

    /** The unit names systemd-timesyncd runs under. */
    public static final Set<String> TIMESYNCD_UNITS = Set.of("systemd-timesyncd", "timesyncd");

    static final Pattern CHRONY_STEP = Pattern.compile(
            "System clock wrong by (?<seconds>[-+]?\\d+(?:\\.\\d+)?) seconds", Pattern.UNICODE_CHARACTER_CLASS);
    static final Pattern TIMESYNCD_SYNC = Pattern.compile("(?:Initial s|S)ynchronization to time server");

    private SyncLines() {}

    static OptionalLong position(LogRecord record) {
        String raw = record.labels().get(MONOTONIC_LABEL);
        if (raw == null) {
            return OptionalLong.empty();
        }
        return io.github.rkaushikdev.trammelworks.Coerce.integral(raw);
    }

    /** Read a chronyd step line, if that is what this is. */
    public static Optional<Observation> observeChrony(LogRecord record, String bootId) {
        Matcher match = CHRONY_STEP.matcher(record.message());
        if (!match.find()) {
            return Optional.empty();
        }
        OptionalLong position = position(record);
        if (position.isEmpty()) {
            return Optional.empty();
        }
        double wrongBy = Double.parseDouble(match.group("seconds"));
        // chronyd's number is (clock - truth). The clock read observedAt, so the truth at that
        // instant was that reading minus the error.
        long errorNs = (long) Math.rint(wrongBy * NanoTime.NS_PER_SECOND);
        SyncEvent event = new SyncEvent(
                bootId,
                position.getAsLong(),
                record.observedAt(),
                record.observedAt().shifted(-errorNs),
                "chrony");
        return Optional.of(new Observation(event, record.unit(), true));
    }

    /** Read a timesyncd sync line, which carries no measurement. */
    public static Optional<Observation> observeTimesyncd(LogRecord record, String bootId) {
        if (!TIMESYNCD_SYNC.matcher(record.message()).find()) {
            return Optional.empty();
        }
        OptionalLong position = position(record);
        if (position.isEmpty()) {
            return Optional.empty();
        }
        SyncEvent event =
                new SyncEvent(bootId, position.getAsLong(), record.observedAt(), record.observedAt(), "timesyncd");
        return Optional.of(new Observation(event, record.unit(), false));
    }

    static boolean looksLikeASync(String message) {
        return CHRONY_STEP.matcher(message).find()
                || TIMESYNCD_SYNC.matcher(message).find();
    }

    /** What the clock should have read, given how wrong chronyd says it was. */
    public static NanoTime truthAt(LogRecord record, double wrongBySeconds) {
        return record.observedAt().shifted(-(long) Math.rint(wrongBySeconds * NanoTime.NS_PER_SECOND));
    }
}
