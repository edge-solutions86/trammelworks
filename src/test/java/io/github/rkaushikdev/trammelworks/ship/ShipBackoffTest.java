package io.github.rkaushikdev.trammelworks.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.ShipError;
import java.util.OptionalDouble;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ShipBackoffTest {

    static final BackoffPolicy POLICY = new BackoffPolicy(2.0, 60.0, 2.0, 5, 600.0);

    static Backoff backoff() {
        return backoff(7);
    }

    static Backoff backoff(long seed) {
        return new Backoff(POLICY, new Random(seed));
    }

    @Test
    void aNonPositiveBaseIsRefused() {
        assertThatThrownBy(() -> new BackoffPolicy(0, 300, 2, 8, 900))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("base must be positive");
    }

    @Test
    void aCeilingBelowTheBaseIsRefused() {
        assertThatThrownBy(() -> new BackoffPolicy(10, 5, 2, 8, 900))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("ceiling cannot be below the base");
    }

    @Test
    void aMultiplierOfOneIsRefused() {
        assertThatThrownBy(() -> new BackoffPolicy(2, 300, 1.0, 8, 900))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("multiplier must be above one");
    }

    @Test
    void aCooldownOfZeroFailuresIsRefused() {
        assertThatThrownBy(() -> new BackoffPolicy(2, 300, 2, 0, 900))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("at least one failure");
    }

    @Test
    void aCooldownShorterThanTheCeilingIsRefused() {
        assertThatThrownBy(() -> new BackoffPolicy(2, 300, 2, 8, 60))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("achieves nothing");
    }

    @Test
    void noFailuresMeansNoWait() {
        assertThat(POLICY.ceilingFor(0)).isEqualTo(0.0);
    }

    @Test
    void theCeilingDoublesWithEachFailure() {
        assertThat(POLICY.ceilingFor(1)).isEqualTo(2.0);
        assertThat(POLICY.ceilingFor(2)).isEqualTo(4.0);
        assertThat(POLICY.ceilingFor(3)).isEqualTo(8.0);
    }

    @Test
    void theCeilingStopsAtItsCap() {
        BackoffPolicy tall = new BackoffPolicy(2, 10, 2, 50, 60);
        assertThat(tall.ceilingFor(9)).isEqualTo(10.0);
    }

    @Test
    void enoughFailuresReachTheCooldown() {
        assertThat(POLICY.ceilingFor(5)).isEqualTo(600.0);
        assertThat(POLICY.ceilingFor(50)).isEqualTo(600.0);
    }

    @Test
    void aWaitIsInsideItsCeiling() {
        Backoff state = backoff();
        for (double expected : new double[] {2.0, 4.0, 8.0}) {
            assertThat(state.fail()).isBetween(0.0, expected);
        }
    }

    @Test
    void theWaitIsJitteredRatherThanFixed() {
        Backoff first = backoff(1);
        Backoff second = backoff(2);
        for (int index = 0; index < 3; index++) {
            first.fail();
            second.fail();
        }
        assertThat(first.lastWait()).isNotEqualTo(second.lastWait());
    }

    @Test
    void twoSitesWithTheSameSeedAgree() {
        assertThat(backoff(5).fail()).isEqualTo(backoff(5).fail());
    }

    @Test
    void successClearsTheFailureCount() {
        Backoff state = backoff();
        state.fail();
        state.fail();
        state.succeed();
        assertThat(state.failures()).isZero();
        assertThat(state.lastWait()).isEqualTo(0.0);
    }

    @Test
    void attemptsCountEveryTry() {
        Backoff state = backoff();
        state.fail();
        state.succeed();
        assertThat(state.attempts()).isEqualTo(2);
    }

    @Test
    void theCooldownFlagFollowsTheFailures() {
        Backoff state = backoff();
        for (int index = 0; index < 4; index++) {
            state.fail();
        }
        assertThat(state.inCooldown()).isFalse();
        state.fail();
        assertThat(state.inCooldown()).isTrue();
    }

    @Test
    void aSuccessLeavesTheCooldown() {
        Backoff state = backoff();
        for (int index = 0; index < 6; index++) {
            state.fail();
        }
        state.succeed();
        assertThat(state.inCooldown()).isFalse();
    }

    @Test
    void aRetryHintIsTakenAsGiven() {
        assertThat(backoff().fail(42.0)).isEqualTo(42.0);
    }

    @Test
    void aRetryHintIsNotJittered() {
        assertThat(backoff(1).fail(30.0)).isEqualTo(backoff(2).fail(30.0));
    }

    @Test
    void aNegativeRetryHintIsRefused() {
        assertThatThrownBy(() -> backoff().fail(-1))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void aGoodOutcomeNeedsNoWait() {
        assertThat(backoff().record(Outcome.OK)).isEqualTo(0.0);
    }

    @Test
    void aRetryableOutcomeWaits() {
        Backoff state = backoff();
        assertThat(state.record(Outcome.RETRYABLE)).isPositive();
        assertThat(state.failures()).isEqualTo(1);
    }

    @Test
    void aThrottledOutcomeUsesTheHint() {
        assertThat(backoff().record(Outcome.THROTTLED, OptionalDouble.of(15.0))).isEqualTo(15.0);
    }

    @Test
    void aThrottledOutcomeWithoutAHintFallsBackToTheCurve() {
        assertThat(backoff().record(Outcome.THROTTLED)).isBetween(0.0, 2.0);
    }

    @Test
    void aTerminalOutcomeDoesNotWaitOrCountAsALinkFailure() {
        Backoff state = backoff();
        assertThat(state.record(Outcome.TERMINAL)).isEqualTo(0.0);
        assertThat(state.failures()).isZero();
        assertThat(state.attempts()).isEqualTo(1);
    }

    @Test
    void terminalOutcomesNeverReachTheCooldown() {
        Backoff state = backoff();
        for (int index = 0; index < 20; index++) {
            state.record(Outcome.TERMINAL);
        }
        assertThat(state.inCooldown()).isFalse();
    }

    @Test
    void resetClearsTheStateWithoutCountingAnAttempt() {
        Backoff state = backoff();
        state.fail();
        int attempts = state.attempts();
        state.reset();
        assertThat(state.failures()).isZero();
        assertThat(state.attempts()).isEqualTo(attempts);
    }

    @Test
    void theWaitAfterARecoveryStartsFromTheBottomAgain() {
        Backoff state = backoff();
        for (int index = 0; index < 6; index++) {
            state.fail();
        }
        state.succeed();
        assertThat(state.fail()).isLessThanOrEqualTo(2.0);
    }
}
