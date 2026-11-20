package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.ShipError;

/**
 * The shape of the wait between attempts: exponential, capped, with a long cooldown once a site
 * has failed many times in a row. On an LTE link that has genuinely gone away, one attempt every
 * few minutes finds the recovery as fast as one every ten seconds.
 *
 * @param baseSeconds the first wait's ceiling
 * @param ceilingSeconds the cap on the ordinary curve
 * @param multiplier how much each failure grows the ceiling
 * @param cooldownAfter how many failures in a row start the cooldown
 * @param cooldownSeconds the cooldown's ceiling
 */
public record BackoffPolicy(
        double baseSeconds, double ceilingSeconds, double multiplier, int cooldownAfter, double cooldownSeconds) {

    /** The defaults: 2 s, doubling, capped at 5 min, cooling down to 15 min after 8 failures. */
    public BackoffPolicy() {
        this(2.0, 300.0, 2.0, 8, 900.0);
    }

    /** Refuses a curve that cannot work. */
    public BackoffPolicy {
        if (baseSeconds <= 0) {
            throw new ShipError(
                    "backoff base must be positive", io.github.rkaushikdev.trammelworks.Repr.of(baseSeconds));
        }
        if (ceilingSeconds < baseSeconds) {
            throw new ShipError("backoff ceiling cannot be below the base");
        }
        if (multiplier <= 1.0) {
            throw new ShipError("backoff multiplier must be above one");
        }
        if (cooldownAfter < 1) {
            throw new ShipError("cooldown_after must be at least one failure");
        }
        if (cooldownSeconds < ceilingSeconds) {
            throw new ShipError("a cooldown shorter than the ceiling achieves nothing");
        }
    }

    /** The unjittered wait after this many consecutive failures. */
    public double ceilingFor(int failures) {
        if (failures <= 0) {
            return 0.0;
        }
        if (failures >= cooldownAfter) {
            return cooldownSeconds;
        }
        double raw = baseSeconds * StrictMath.pow(multiplier, failures - 1);
        return Math.min(raw, ceilingSeconds);
    }
}
