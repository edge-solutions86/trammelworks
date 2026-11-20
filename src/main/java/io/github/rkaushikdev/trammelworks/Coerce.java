package io.github.rkaushikdev.trammelworks;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;

/**
 * Turning loosely typed config values into the types a section needs.
 *
 * <p>A config value arrives as whatever the YAML parser or an environment override made of it, so
 * {@code keep_last: 4}, {@code keep_last: "4"} and an override of {@code 4} all have to read as
 * four. The conversions follow the original tooling's rules (a string like {@code " 21 "} is a
 * number, {@code 3.9} truncates to 3, any non-empty string is true), and a value that cannot be
 * converted is a {@link ConfigError} naming what was there.
 */
public final class Coerce {

    private Coerce() {}

    /** A whole number. */
    public static long toLong(Object raw) {
        return switch (raw) {
            case Boolean flag -> flag ? 1 : 0;
            case Integer value -> value;
            case Long value -> value;
            case BigInteger value -> value.longValueExact();
            case Double value -> {
                if (value.isNaN() || value.isInfinite()) {
                    throw new ConfigError("not a whole number", Repr.of(value));
                }
                yield (long) value.doubleValue();
            }
            case String text -> parseInteger(text);
            case null -> throw new ConfigError("not a whole number", "None");
            default -> throw new ConfigError("not a whole number", String.valueOf(raw));
        };
    }

    /** A whole number that fits an {@code int}. */
    public static int toInt(Object raw) {
        long value = toLong(raw);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ConfigError("number is out of range", Long.toString(value));
        }
        return (int) value;
    }

    private static long parseInteger(String text) {
        String cleaned = text.strip();
        if (cleaned.matches("[+-]?\\d+(?:_\\d+)*")) {
            try {
                return Long.parseLong(cleaned.replace("_", "").replace("+", ""));
            } catch (NumberFormatException exc) {
                throw new ConfigError("number is out of range", text);
            }
        }
        throw new ConfigError("not a whole number", text);
    }

    /**
     * An integer written as text the way the reference integer parser reads it: surrounding
     * whitespace allowed, an optional sign, digits with single underscores between them.
     */
    public static java.util.OptionalLong integral(String text) {
        String cleaned = text.strip();
        if (!cleaned.matches("[+-]?\\d+(?:_\\d+)*")) {
            return java.util.OptionalLong.empty();
        }
        try {
            return java.util.OptionalLong.of(
                    Long.parseLong(cleaned.replace("_", "").replace("+", "")));
        } catch (NumberFormatException exc) {
            return java.util.OptionalLong.empty();
        }
    }

    /** A float. */
    public static double toDouble(Object raw) {
        return switch (raw) {
            case Boolean flag -> flag ? 1.0 : 0.0;
            case Number value -> value.doubleValue();
            case String text -> {
                try {
                    yield Double.parseDouble(text.strip().replace("_", ""));
                } catch (NumberFormatException exc) {
                    throw new ConfigError("not a number", text);
                }
            }
            case null -> throw new ConfigError("not a number", "None");
            default -> throw new ConfigError("not a number", String.valueOf(raw));
        };
    }

    /** Truthiness: false, zero, empty and absent are false, everything else is true. */
    public static boolean toBool(Object raw) {
        return switch (raw) {
            case null -> false;
            case Boolean flag -> flag;
            case Number value -> value.doubleValue() != 0.0;
            case String text -> !text.isEmpty();
            case Collection<?> items -> !items.isEmpty();
            case Map<?, ?> items -> !items.isEmpty();
            default -> true;
        };
    }

    /** The text form a value prints as. */
    public static String text(Object raw) {
        return switch (raw) {
            case null -> "None";
            case Boolean flag -> flag ? "True" : "False";
            case Double value -> Repr.of(value);
            case Float value -> Repr.of(value.doubleValue());
            default -> raw.toString();
        };
    }
}
