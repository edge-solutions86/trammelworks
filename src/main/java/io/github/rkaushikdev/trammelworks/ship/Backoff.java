package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.ShipError;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * One site's retrying, with full jitter.
 *
 * <p>The jitter is not decoration: an estate is thousands of forecourts on one collector, and a
 * regional outage brings them all back at once. Without jitter they retry in lockstep and knock the
 * collector over again. A collector's own retry hint is taken as given rather than jittered, since
 * the point of the hint is that every site waits until the collector is ready.
 */
public final class Backoff {

    private final BackoffPolicy policy;
    private final RandomGenerator rng;
    private int failures;
    private int attempts;
    private double lastWait;

    /** The default policy and an unseeded generator. */
    public Backoff() {
        this(new BackoffPolicy(), new Random());
    }

    /** A given policy and generator. */
    public Backoff(BackoffPolicy policy, RandomGenerator rng) {
        this.policy = policy;
        this.rng = rng;
    }

    /** The policy. */
    public BackoffPolicy policy() {
        return policy;
    }

    /** Failures in a row. */
    public int failures() {
        return failures;
    }

    /** Every attempt counted. */
    public int attempts() {
        return attempts;
    }

    /** The last wait handed out. */
    public double lastWait() {
        return lastWait;
    }

    /** Whether the site is in its long cooldown. */
    public boolean inCooldown() {
        return failures >= policy.cooldownAfter();
    }

    /** An attempt worked. Everything resets. */
    public void succeed() {
        attempts++;
        failures = 0;
        lastWait = 0.0;
    }

    /** An attempt failed. Returns how long to wait, jittered under the curve. */
    public double fail() {
        return fail(OptionalDouble.empty());
    }

    /** An attempt failed and the collector said how long to wait. */
    public double fail(double retryAfter) {
        return fail(OptionalDouble.of(retryAfter));
    }

    private double fail(OptionalDouble retryAfter) {
        attempts++;
        failures++;
        if (retryAfter.isPresent()) {
            if (retryAfter.getAsDouble() < 0) {
                throw new ShipError("retry_after cannot be negative", Repr.of(retryAfter.getAsDouble()));
            }
            lastWait = retryAfter.getAsDouble();
            return lastWait;
        }
        double ceiling = policy.ceilingFor(failures);
        lastWait = ceiling * rng.nextDouble();
        return lastWait;
    }

    /** Fold an outcome in. Returns the wait, which is zero when there is none. */
    public double record(Outcome outcome) {
        return record(outcome, OptionalDouble.empty());
    }

    /** Fold an outcome in, with the collector's hint when it gave one. */
    public double record(Outcome outcome, OptionalDouble retryAfter) {
        return switch (outcome) {
            case OK -> {
                succeed();
                yield 0.0;
            }
            case TERMINAL -> {
                // Not a link problem. Counting it would push a healthy site into a cooldown because
                // one malformed batch keeps being refused.
                attempts++;
                lastWait = 0.0;
                yield 0.0;
            }
            case THROTTLED -> fail(retryAfter);
            case RETRYABLE -> fail(OptionalDouble.empty());
        };
    }

    /** Clear the failures without counting an attempt. */
    public void reset() {
        failures = 0;
        lastWait = 0.0;
    }
}
