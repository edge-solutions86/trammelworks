package io.github.rkaushikdev.trammelworks.redact;

/**
 * The Luhn check.
 *
 * <p>Every payment card number ends in a check digit computed this way, so Luhn is the cheapest
 * filter there is between "sixteen digits" and "a card number". It is a filter and not a decision:
 * one number in ten passes by chance. It works on the digit string rather than an integer on
 * purpose, because leading zeros are significant in a PAN.
 */
public final class Luhn {

    /** The shortest card number there is. */
    public static final int MIN_PAN_DIGITS = 13;

    /** The longest card number there is. */
    public static final int MAX_PAN_DIGITS = 19;

    private Luhn() {}

    /** Strip the separators a terminal prints between groups. */
    public static String digitsOnly(String raw) {
        StringBuilder out = new StringBuilder();
        raw.codePoints().filter(Character::isDigit).forEach(out::appendCodePoint);
        return out.toString();
    }

    static boolean isDigits(String text) {
        return !text.isEmpty() && text.codePoints().allMatch(Character::isDigit);
    }

    /** The Luhn total for a digit string, check digit included. */
    public static int luhnSum(String digits) {
        if (!isDigits(digits)) {
            throw new IllegalArgumentException("luhn needs a non empty digit string");
        }
        int[] points = digits.codePoints().toArray();
        int total = 0;
        for (int position = 0; position < points.length; position++) {
            int value = points[points.length - 1 - position] - '0';
            if (position % 2 == 1) {
                value *= 2;
                if (value > 9) {
                    value -= 9;
                }
            }
            total += value;
        }
        return total;
    }

    /** True when the string carries a correct Luhn check digit. */
    public static boolean luhnValid(String digits) {
        if (!isDigits(digits) || digits.codePointCount(0, digits.length()) < 2) {
            return false;
        }
        return luhnSum(digits) % 10 == 0;
    }

    /** The digit that would make {@code body} valid, used by the test corpus. */
    public static String luhnCheckDigit(String body) {
        if (!isDigits(body)) {
            throw new IllegalArgumentException("luhn needs a digit string");
        }
        int remainder = luhnSum(body + "0") % 10;
        return Integer.toString((10 - remainder) % 10);
    }

    /** True for a length a real card number can have. */
    public static boolean plausibleLength(String digits) {
        int length = digits.codePointCount(0, digits.length());
        return MIN_PAN_DIGITS <= length && length <= MAX_PAN_DIGITS;
    }
}
