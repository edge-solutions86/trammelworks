package io.github.rkaushikdev.trammelworks;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Text renderings that operators and the estate already parse.
 *
 * <p>Error messages quote names the way the original tooling did ({@code 'pump-bridge'}), floats
 * print in their shortest round-tripping form ({@code 0.25}, {@code 1e-05}), and fixed-point
 * figures round the exact binary value half to even. Java's own formatting differs on every one of
 * those, so the conversions live here.
 */
public final class Repr {

    private Repr() {}

    /** A string quoted the way an error message names it. */
    public static String of(String text) {
        char quote = text.indexOf('\'') >= 0 && text.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder out = new StringBuilder().append(quote);
        text.codePoints().forEach(point -> {
            if (point == quote || point == '\\') {
                out.append('\\').appendCodePoint(point);
            } else if (point == '\n') {
                out.append("\\n");
            } else if (point == '\r') {
                out.append("\\r");
            } else if (point == '\t') {
                out.append("\\t");
            } else if (printable(point)) {
                out.appendCodePoint(point);
            } else if (point < 0x100) {
                out.append(String.format("\\x%02x", point));
            } else if (point < 0x10000) {
                out.append(String.format("\\u%04x", point));
            } else {
                out.append(String.format("\\U%08x", point));
            }
        });
        return out.append(quote).toString();
    }

    private static boolean printable(int point) {
        if (point == ' ') {
            return true;
        }
        int type = Character.getType(point);
        return type != Character.CONTROL
                && type != Character.FORMAT
                && type != Character.SURROGATE
                && type != Character.PRIVATE_USE
                && type != Character.UNASSIGNED
                && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR
                && type != Character.SPACE_SEPARATOR;
    }

    /** The shortest text that reads back as the same double, in the reference spelling. */
    public static String of(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        String sign = (value < 0 || (value == 0.0 && 1.0 / value < 0)) ? "-" : "";
        double magnitude = Math.abs(value);
        if (magnitude == 0.0) {
            return sign + "0.0";
        }
        BigDecimal exact = new BigDecimal(Double.toString(magnitude)).stripTrailingZeros();
        String digits = exact.unscaledValue().toString();
        int point = digits.length() - exact.scale();
        if (point <= -4 || point > 16) {
            StringBuilder out = new StringBuilder(sign).append(digits.charAt(0));
            if (digits.length() > 1) {
                out.append('.').append(digits, 1, digits.length());
            }
            int exponent = point - 1;
            out.append('e').append(exponent < 0 ? '-' : '+');
            String power = Integer.toString(Math.abs(exponent));
            return out.append(power.length() < 2 ? "0" + power : power).toString();
        }
        if (point <= 0) {
            return sign + "0." + "0".repeat(-point) + digits;
        }
        if (point >= digits.length()) {
            return sign + digits + "0".repeat(point - digits.length()) + ".0";
        }
        return sign + digits.substring(0, point) + "." + digits.substring(point);
    }

    /** {@code value} to {@code places} decimals, rounding the exact binary value half to even. */
    public static String fixed(double value, int places) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        String body =
                new BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).toPlainString();
        boolean negativeZero = value == 0.0 && 1.0 / value < 0;
        if ((negativeZero || value < 0) && !body.startsWith("-")) {
            body = "-" + body;
        }
        return body;
    }

    /** {@link #fixed} with an explicit sign on positive values, as a {@code +} format gives. */
    public static String signedFixed(double value, int places) {
        String body = fixed(value, places);
        return body.startsWith("-") ? body : "+" + body;
    }
}
