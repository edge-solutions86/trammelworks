package io.github.rkaushikdev.trammelworks;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Byte sizes and durations as they are written in a site configuration file.
 *
 * <p>Commissioning engineers write {@code spool_budget: 512MiB} and {@code flush_every: 90s} into a
 * YAML file on a laptop in a car park. Both spellings have to round trip so that a value read back
 * out of a status report is the same string that went in.
 *
 * <p>Binary and decimal suffixes are both accepted and they mean different things. The LTE plan is
 * sold in decimal megabytes and the disk is formatted in binary mebibytes, so a parser that quietly
 * treats them as equal is off by 4.8% on the one number that costs money.
 */
public final class Units {

    /** Every byte suffix, lower case, with its multiplier. */
    public static final Map<String, Long> BYTE_SUFFIXES = ordered(
            "b", 1L,
            "kb", 1000L,
            "mb", 1000L * 1000,
            "gb", 1000L * 1000 * 1000,
            "tb", 1000L * 1000 * 1000 * 1000,
            "kib", 1024L,
            "mib", 1024L * 1024,
            "gib", 1024L * 1024 * 1024,
            "tib", 1024L * 1024 * 1024 * 1024);

    /** Every duration suffix with its length in seconds. */
    public static final Map<String, Double> DURATION_SUFFIXES =
            Map.of("ms", 0.001, "s", 1.0, "m", 60.0, "h", 3600.0, "d", 86400.0, "w", 604800.0);

    private static final Pattern BYTES = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*([a-z]*)$");
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|[smhdw])");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private static final String[] BINARY_NAMES = {"TiB", "GiB", "MiB", "KiB"};
    private static final long[] BINARY_SCALES = {1024L * 1024 * 1024 * 1024, 1024L * 1024 * 1024, 1024L * 1024, 1024L};
    private static final String[] DURATION_NAMES = {"w", "d", "h", "m", "s"};
    private static final long[] DURATION_SCALES = {604800, 86400, 3600, 60, 1};

    private Units() {}

    private static Map<String, Long> ordered(Object... pairs) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            out.put((String) pairs[index], (Long) pairs[index + 1]);
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** A byte count written as a bare integer. */
    public static long parseBytes(long raw) {
        if (raw < 0) {
            throw new ConfigError("negative byte size", Long.toString(raw));
        }
        return raw;
    }

    /** Parse {@code 512MiB}, {@code 1.5 GB} or a bare integer into a byte count. */
    public static long parseBytes(String raw) {
        Matcher match = BYTES.matcher(Text.strip(raw).toLowerCase(Locale.ROOT));
        if (!match.find()) {
            throw new ConfigError("malformed byte size", raw);
        }
        String suffix = match.group(2).isEmpty() ? "b" : match.group(2);
        Long scale = BYTE_SUFFIXES.get(suffix);
        if (scale == null) {
            throw new ConfigError("unknown byte suffix", raw);
        }
        double value = Double.parseDouble(match.group(1)) * scale;
        if (value != Math.floor(value)) {
            throw new ConfigError("byte size is not a whole number of bytes", raw);
        }
        return (long) value;
    }

    /** A byte count as a config file may hold it: a number or a spelled size. */
    public static long parseBytes(Object raw) {
        return switch (raw) {
            case Integer value -> parseBytes(value.longValue());
            case Long value -> parseBytes(value.longValue());
            case String value -> parseBytes(value);
            case null -> throw new ConfigError("malformed byte size", "None");
            default -> throw new ConfigError("malformed byte size", String.valueOf(raw));
        };
    }

    /** Render a byte count using the largest binary suffix that stays exact. */
    public static String formatBytes(long count) {
        if (count < 0) {
            throw new ConfigError("negative byte size", Long.toString(count));
        }
        for (int index = 0; index < BINARY_SCALES.length; index++) {
            long scale = BINARY_SCALES[index];
            if (count >= scale && count % scale == 0) {
                return count / scale + BINARY_NAMES[index];
            }
        }
        return count + "B";
    }

    /** A duration written as a bare number of seconds. */
    public static Duration parseDuration(double seconds) {
        return number(seconds, Repr.of(seconds));
    }

    private static Duration number(double seconds, String shown) {
        if (seconds < 0) {
            throw new ConfigError("negative duration", shown);
        }
        return ofSeconds(seconds);
    }

    /** Parse {@code 90s}, {@code 2h30m} or a bare number of seconds. */
    public static Duration parseDuration(String raw) {
        String text = Text.strip(raw).toLowerCase(Locale.ROOT).replace(" ", "");
        if (text.isEmpty()) {
            throw new ConfigError("empty duration", raw);
        }
        if (DIGITS.matcher(text).matches()) {
            return Duration.ofSeconds(Long.parseLong(text));
        }
        double total = 0.0;
        int consumed = 0;
        Matcher match = DURATION_PART.matcher(text);
        while (match.find()) {
            if (match.start() != consumed) {
                throw new ConfigError("malformed duration", raw);
            }
            total += Double.parseDouble(match.group(1)) * DURATION_SUFFIXES.get(match.group(2));
            consumed = match.end();
        }
        if (consumed != text.length()) {
            throw new ConfigError("malformed duration", raw);
        }
        return ofSeconds(total);
    }

    /** A duration as a config file may hold it: a number of seconds or a spelled duration. */
    public static Duration parseDuration(Object raw) {
        return switch (raw) {
            case Integer value -> number(value.doubleValue(), value.toString());
            case Long value -> number(value.doubleValue(), value.toString());
            case Double value -> parseDuration(value.doubleValue());
            case String value -> parseDuration(value);
            case null -> throw new ConfigError("malformed duration", "None");
            default -> throw new ConfigError("malformed duration", String.valueOf(raw));
        };
    }

    /** A float number of seconds, rounded to the microsecond. */
    public static Duration ofSeconds(double seconds) {
        double whole = Math.floor(seconds);
        long micros = (long) Math.rint((seconds - whole) * 1_000_000);
        return Duration.ofSeconds((long) whole).plusNanos(micros * 1_000);
    }

    /** A duration as a float number of seconds, to the microsecond. */
    public static double totalSeconds(Duration value) {
        long micros = value.getSeconds() * 1_000_000 + value.getNano() / 1_000;
        return micros / 1e6;
    }

    /** Render a duration back into the compact spelling. */
    public static String formatDuration(Duration value) {
        double seconds = totalSeconds(value);
        if (seconds < 0) {
            throw new ConfigError("negative duration", describe(value));
        }
        if (seconds == 0) {
            return "0s";
        }
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < DURATION_SCALES.length; index++) {
            long scale = DURATION_SCALES[index];
            long whole = (long) floorDivide(seconds, scale);
            if (whole != 0) {
                out.append(whole).append(DURATION_NAMES[index]);
                seconds -= whole * (double) scale;
            }
        }
        if (seconds != 0) {
            out.append((long) Math.rint(seconds * 1000)).append("ms");
        }
        return out.toString();
    }

    /** How a negative or odd duration is named in an error: days, then clock time. */
    static String describe(Duration value) {
        long micros = value.getSeconds() * 1_000_000 + value.getNano() / 1_000;
        long days = Math.floorDiv(micros, 86_400_000_000L);
        long rest = Math.floorMod(micros, 86_400_000_000L);
        long secs = rest / 1_000_000;
        long fraction = rest % 1_000_000;
        String clock = String.format(Locale.ROOT, "%d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60);
        if (fraction != 0) {
            clock += String.format(Locale.ROOT, ".%06d", fraction);
        }
        if (days == 0) {
            return clock;
        }
        return days + (Math.abs(days) == 1 ? " day, " : " days, ") + clock;
    }

    /** Floor division of two doubles: the quotient rounds down and the remainder takes the divisor's sign. */
    static double floorDivide(double numerator, double denominator) {
        double mod = numerator % denominator;
        double div = (numerator - mod) / denominator;
        if (mod != 0 && (denominator < 0) != (mod < 0)) {
            div -= 1.0;
        }
        if (div == 0) {
            return Math.copySign(0.0, numerator / denominator);
        }
        double floor = Math.floor(div);
        if (div - floor > 0.5) {
            floor += 1.0;
        }
        return floor;
    }
}
