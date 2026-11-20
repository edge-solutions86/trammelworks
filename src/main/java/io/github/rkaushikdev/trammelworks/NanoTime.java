package io.github.rkaushikdev.trammelworks;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An instant, to the nanosecond, counted from the Unix epoch.
 *
 * <p>The CRI runtime writes RFC3339 with nine fractional digits. The payment kernel emits an
 * authorisation request and its response inside the same microsecond often enough that dropping the
 * last three digits reorders them, and a reordered auth pair is exactly the evidence a chargeback
 * review asks for. So the internal representation is an integer count of nanoseconds, and calendar
 * types appear only where something has to be rendered, with the conversion explicit.
 *
 * @param epochNs nanoseconds since 1970-01-01T00:00:00Z
 */
public record NanoTime(long epochNs) implements Comparable<NanoTime> {

    /** Nanoseconds in a microsecond. */
    public static final long NS_PER_US = 1_000L;
    /** Nanoseconds in a millisecond. */
    public static final long NS_PER_MS = 1_000_000L;
    /** Nanoseconds in a second. */
    public static final long NS_PER_SECOND = 1_000_000_000L;

    private static final Pattern RFC3339 = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})"
            + "[Tt ](\\d{2}):(\\d{2}):(\\d{2})"
            + "(?:\\.(\\d{1,9}))?"
            + "(Z|z|[+-]\\d{2}:?\\d{2})$");

    private static final DateTimeFormatter STEM = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private static final long DAY_SECONDS = 86_400L;

    /** Parse an RFC3339 timestamp with zero to nine fractional digits. */
    public static NanoTime parse(String raw) {
        Matcher match = RFC3339.matcher(Text.strip(raw));
        if (!match.find()) {
            throw new ParseError("malformed rfc3339 timestamp", raw);
        }
        long offsetSeconds = offset(match.group(8));
        LocalDateTime base;
        try {
            int year = Integer.parseInt(match.group(1));
            if (year < 1 || Math.abs(offsetSeconds) >= DAY_SECONDS) {
                throw new DateTimeException("out of range");
            }
            base = LocalDateTime.of(
                    year,
                    Integer.parseInt(match.group(2)),
                    Integer.parseInt(match.group(3)),
                    Integer.parseInt(match.group(4)),
                    Integer.parseInt(match.group(5)),
                    Integer.parseInt(match.group(6)));
        } catch (DateTimeException exc) {
            throw new ParseError("impossible rfc3339 timestamp", raw);
        }
        String fraction = match.group(7);
        long nanos = fraction == null ? 0 : Long.parseLong((fraction + "000000000").substring(0, 9));
        long whole = base.toEpochSecond(ZoneOffset.UTC) - offsetSeconds;
        return new NanoTime(whole * NS_PER_SECOND + nanos);
    }

    private static long offset(String raw) {
        if (raw.equals("Z") || raw.equals("z")) {
            return 0;
        }
        int sign = raw.charAt(0) == '-' ? -1 : 1;
        String body = raw.substring(1).replace(":", "");
        long hours = Long.parseLong(body.substring(0, 2));
        long minutes = Long.parseLong(body.substring(2));
        return sign * (hours * 3600 + minutes * 60);
    }

    /**
     * The instant an offset-aware date and time names, to the microsecond.
     *
     * @throws ParseError when the value carries no offset, since a local time names no instant
     */
    public static NanoTime fromDateTime(TemporalAccessor value) {
        if (!value.isSupported(ChronoField.INSTANT_SECONDS)) {
            throw new ParseError("naive datetime has no instant", value.toString());
        }
        long seconds = value.getLong(ChronoField.INSTANT_SECONDS);
        long micros = value.isSupported(ChronoField.MICRO_OF_SECOND) ? value.getLong(ChronoField.MICRO_OF_SECOND) : 0;
        return new NanoTime(seconds * NS_PER_SECOND + micros * NS_PER_US);
    }

    /** journald counts microseconds since the epoch, so accept that too. */
    public static NanoTime fromMicros(long micros) {
        return new NanoTime(micros * NS_PER_US);
    }

    /** Whole seconds since the epoch, rounded towards the past. */
    public long seconds() {
        return Math.floorDiv(epochNs, NS_PER_SECOND);
    }

    /** The nanoseconds past {@link #seconds()}, always between 0 and 999,999,999. */
    public long nanosInSecond() {
        return Math.floorMod(epochNs, NS_PER_SECOND);
    }

    /** An aware UTC date and time, truncating below the microsecond. */
    public OffsetDateTime toDateTime() {
        long micros = nanosInSecond() / NS_PER_US;
        return Instant.ofEpochSecond(seconds(), micros * NS_PER_US).atOffset(ZoneOffset.UTC);
    }

    /** Render with all nine fractional digits, always in UTC. */
    public String isoformat() {
        String stem = STEM.format(Instant.ofEpochSecond(seconds()).atOffset(ZoneOffset.UTC));
        return stem + "." + String.format("%09d", nanosInSecond()) + "Z";
    }

    /** This instant moved by a signed number of nanoseconds. */
    public NanoTime shifted(long deltaNs) {
        return new NanoTime(epochNs + deltaNs);
    }

    /** The difference in nanoseconds, which is what drift arithmetic wants. */
    public long minus(NanoTime other) {
        return epochNs - other.epochNs;
    }

    /** Whether this instant is strictly before another. */
    public boolean isBefore(NanoTime other) {
        return epochNs < other.epochNs;
    }

    /** Whether this instant is strictly after another. */
    public boolean isAfter(NanoTime other) {
        return epochNs > other.epochNs;
    }

    @Override
    public int compareTo(NanoTime other) {
        return Long.compare(epochNs, other.epochNs);
    }

    @Override
    public String toString() {
        return isoformat();
    }
}
