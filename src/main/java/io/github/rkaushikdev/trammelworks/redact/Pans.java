package io.github.rkaushikdev.trammelworks.redact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finding card numbers in a line of log output.
 *
 * <p>Three tests have to pass together before a run of digits is treated as a PAN, and each one on
 * its own is useless: a length of 13 to 19 digits, the Luhn check, and an issuer prefix we
 * recognise. A forecourt controller prints a lot of long numbers (transaction references, EMV tags,
 * tank volumes in millilitres), and fuel cards are why the prefix table is not the usual four
 * brands: an unrecognised fleet PAN ships off site in the clear.
 *
 * <p>The scan keeps spans, because the replacement has to go back exactly where the digits were.
 * Single separators inside a group are allowed, because terminals print {@code 4111 1111 1111
 * 1111}.
 */
public final class Pans {

    /** Issuer prefixes seen across the estate, fuel and fleet ranges included. */
    public static final Map<String, List<String>> ISSUER_PREFIXES = prefixes();

    static final Pattern CANDIDATE = Pattern.compile("\\d(?:[ -]?\\d){11,22}", Pattern.UNICODE_CHARACTER_CLASS);

    /** What a hidden digit becomes. */
    public static final char MASK_CHARACTER = '*';

    /** Leading digits the card schemes allow to be stored. */
    public static final int KEEP_FIRST = 6;

    /** Trailing digits the card schemes allow to be stored. */
    public static final int KEEP_LAST = 4;

    /**
     * A mask that hides only a digit or two is not a mask. If keeping the head and tail would leave
     * fewer than this many hidden, everything goes instead.
     */
    public static final int MIN_MASKED = 4;

    private Pans() {}

    private static Map<String, List<String>> prefixes() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("visa", List.of("4"));
        out.put("mastercard", List.of("51", "52", "53", "54", "55", "22", "23", "24", "25", "26", "27"));
        out.put("amex", List.of("34", "37"));
        out.put("discover", List.of("6011", "65", "644", "645", "646", "647", "648", "649"));
        out.put("diners", List.of("300", "301", "302", "303", "304", "305", "36", "38"));
        out.put("jcb", List.of("35"));
        out.put("maestro", List.of("50", "56", "57", "58", "6759", "6761", "6762", "6763"));
        // Fleet and fuel. 7-prefixed ranges are private label cards issued by the fuel brands
        // themselves and they are the bulk of forecourt traffic.
        out.put("fleet", List.of("7", "708", "70251", "70256"));
        out.put("wex", List.of("690046", "707138"));
        out.put("voyager", List.of("8699"));
        return java.util.Collections.unmodifiableMap(out);
    }

    /** The issuer whose range this number falls in, longest prefix wins. */
    public static Optional<String> brandOf(String digits) {
        String best = null;
        int bestLength = -1;
        for (Map.Entry<String, List<String>> entry : ISSUER_PREFIXES.entrySet()) {
            for (String prefix : entry.getValue()) {
                if (digits.startsWith(prefix) && prefix.length() > bestLength) {
                    best = entry.getKey();
                    bestLength = prefix.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** All three tests, in the order that rejects fastest. */
    public static boolean looksLikePan(String digits) {
        return Luhn.plausibleLength(digits)
                && Luhn.luhnValid(digits)
                && brandOf(digits).isPresent();
    }

    /** Every card number in a line, left to right and non overlapping. */
    public static List<PanMatch> findPans(String text) {
        List<PanMatch> found = new ArrayList<>();
        Matcher match = CANDIDATE.matcher(text);
        while (match.find()) {
            String raw = match.group();
            String digits = Luhn.digitsOnly(raw);
            if (looksLikePan(digits)) {
                found.add(new PanMatch(
                        match.start(), match.end(), raw, digits, brandOf(digits).orElse("unknown")));
                continue;
            }
            trimToPan(raw, match.start()).ifPresent(found::add);
        }
        return found;
    }

    /**
     * Try shorter prefixes of an over long run. A terminal that prints a PAN immediately followed by
     * an expiry gives one run of twenty digits; the whole run fails but its first sixteen are a card.
     */
    private static Optional<PanMatch> trimToPan(String raw, int offset) {
        List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < raw.length(); index++) {
            if (Character.isDigit(raw.charAt(index))) {
                positions.add(index);
            }
        }
        for (int count = positions.size(); count > 12; count--) {
            int end = positions.get(count - 1) + 1;
            String candidate = raw.substring(0, end);
            String digits = Luhn.digitsOnly(candidate);
            if (looksLikePan(digits)) {
                return Optional.of(new PanMatch(
                        offset, offset + end, candidate, digits, brandOf(digits).orElse("unknown")));
            }
        }
        return Optional.empty();
    }

    /** Mask a PAN, keeping the six and four digits the card schemes allow to be stored. */
    public static String maskDigits(String digits) {
        return maskDigits(digits, KEEP_FIRST, KEEP_LAST);
    }

    /** Mask a PAN, keeping the given numbers of leading and trailing digits. */
    public static String maskDigits(String digits, int keepFirst, int keepLast) {
        if (keepFirst < 0 || keepLast < 0) {
            throw new IllegalArgumentException("cannot keep a negative number of digits");
        }
        int hidden = digits.length() - keepFirst - keepLast;
        if (hidden < MIN_MASKED) {
            return String.valueOf(MASK_CHARACTER).repeat(digits.length());
        }
        String head = digits.substring(0, keepFirst);
        String tail = keepLast > 0 ? digits.substring(digits.length() - keepLast) : "";
        return head + String.valueOf(MASK_CHARACTER).repeat(hidden) + tail;
    }

    /** Replace every card number in a line with its masked form. */
    public static String maskPans(String text) {
        return maskPans(text, KEEP_FIRST, KEEP_LAST);
    }

    /** Replace every card number in a line, keeping the given digits. */
    public static String maskPans(String text, int keepFirst, int keepLast) {
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (PanMatch found : findPans(text)) {
            out.append(text, cursor, found.start());
            out.append(maskDigits(found.digits(), keepFirst, keepLast));
            cursor = found.end();
        }
        out.append(text.substring(cursor));
        return out.toString();
    }
}
