package io.github.rkaushikdev.trammelworks.redact;

/**
 * A card number found in a line, and where it was.
 *
 * @param start where the number starts in the line
 * @param end just past its last digit
 * @param text the number as it was printed, separators included
 * @param digits the number's digits alone
 * @param brand the issuer whose range it falls in
 */
public record PanMatch(int start, int end, String text, String digits, String brand) {

    /** How many digits the number has. */
    public int length() {
        return digits.length();
    }
}
