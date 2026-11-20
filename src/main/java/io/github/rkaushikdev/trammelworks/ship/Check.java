package io.github.rkaushikdev.trammelworks.ship;

/**
 * Why a signature was accepted or refused.
 *
 * @param ok whether it verified
 * @param reason why not, when it did not
 */
public record Check(boolean ok, String reason) {

    static Check passed() {
        return new Check(true, "");
    }

    static Check refused(String reason) {
        return new Check(false, reason);
    }
}
