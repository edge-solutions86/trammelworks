package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClocksTest {

    @Test
    void systemClockSatisfiesTheProtocol() {
        assertThat(new SystemClock()).isInstanceOf(Clock.class);
    }

    @Test
    void manualClockSatisfiesTheProtocol() {
        assertThat(new ManualClock()).isInstanceOf(Clock.class);
    }

    @Test
    void systemClockWallReadingIsPlausible() {
        assertThat(new SystemClock().now()).isGreaterThan(NanoTime.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void systemClockMonotonicNeverGoesBackwards() {
        SystemClock clock = new SystemClock();
        long first = clock.monotonicNs();
        long second = clock.monotonicNs();
        assertThat(second).isGreaterThanOrEqualTo(first);
    }

    @Test
    void manualClockStartsAtTheEpoch() {
        ManualClock clock = new ManualClock();
        assertThat(clock.now()).isEqualTo(new NanoTime(0));
        assertThat(clock.monotonicNs()).isZero();
    }

    @Test
    void advanceMovesBothReadingsTogether() {
        ManualClock clock = new ManualClock();
        clock.advance(1.5);
        assertThat(clock.now().epochNs()).isEqualTo(1_500_000_000L);
        assertThat(clock.monotonicNs()).isEqualTo(1_500_000_000L);
    }

    @Test
    void advanceAccumulates() {
        ManualClock clock = new ManualClock();
        clock.advance(1);
        clock.advance(2);
        assertThat(clock.monotonicNs()).isEqualTo(3 * NanoTime.NS_PER_SECOND);
    }

    @Test
    void advanceRefusesToGoBackwards() {
        assertThatThrownBy(() -> new ManualClock().advance(-1))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("cannot advance backwards");
    }

    @Test
    void stepWallLeavesTheMonotonicCounterAlone() {
        ManualClock clock = new ManualClock();
        clock.advance(10);
        clock.stepWall(-4);
        assertThat(clock.now().epochNs()).isEqualTo(6 * NanoTime.NS_PER_SECOND);
        assertThat(clock.monotonicNs()).isEqualTo(10 * NanoTime.NS_PER_SECOND);
    }

    @Test
    void stepWallCanJumpForwardsLikeAnNtpCorrection() {
        ManualClock clock = new ManualClock();
        clock.stepWall(3600);
        assertThat(clock.now().epochNs()).isEqualTo(3600 * NanoTime.NS_PER_SECOND);
        assertThat(clock.monotonicNs()).isZero();
    }

    @Test
    void setWallAcceptsANanotime() {
        ManualClock clock = new ManualClock();
        clock.setWall(NanoTime.parse("2025-03-04T11:30:00Z"));
        assertThat(clock.now().isoformat()).isEqualTo("2025-03-04T11:30:00.000000000Z");
    }

    @Test
    void setWallAcceptsAString() {
        ManualClock clock = new ManualClock();
        clock.setWall("2025-03-04T11:30:00.25Z");
        assertThat(clock.now().nanosInSecond()).isEqualTo(250000000);
    }

    @Test
    void deadlineAfterIsRelativeToTheMonotonicReading() {
        ManualClock clock = new ManualClock();
        clock.advance(100);
        Deadline deadline = Deadline.after(clock, 30);
        assertThat(deadline.atNs()).isEqualTo(130 * NanoTime.NS_PER_SECOND);
    }

    @Test
    void deadlineRefusesANegativeOffset() {
        assertThatThrownBy(() -> Deadline.after(new ManualClock(), -1))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("cannot be in the past");
    }

    @Test
    void deadlineIsNotExpiredBeforeItLands() {
        ManualClock clock = new ManualClock();
        Deadline deadline = Deadline.after(clock, 10);
        clock.advance(9.9);
        assertThat(deadline.expired(clock)).isFalse();
    }

    @Test
    void deadlineExpiresExactlyOnTheBoundary() {
        ManualClock clock = new ManualClock();
        Deadline deadline = Deadline.after(clock, 10);
        clock.advance(10);
        assertThat(deadline.expired(clock)).isTrue();
    }

    @Test
    void deadlineRemainingCountsDown() {
        ManualClock clock = new ManualClock();
        Deadline deadline = Deadline.after(clock, 10);
        clock.advance(4);
        assertThat(deadline.remaining(clock)).isCloseTo(6.0, within(1e-6));
    }

    @Test
    void deadlineRemainingClampsAtZero() {
        ManualClock clock = new ManualClock();
        Deadline deadline = Deadline.after(clock, 1);
        clock.advance(30);
        assertThat(deadline.remaining(clock)).isEqualTo(0.0);
    }

    @Test
    void deadlineIgnoresAWallClockJump() {
        ManualClock clock = new ManualClock();
        Deadline deadline = Deadline.after(clock, 10);
        clock.stepWall(-86400);
        assertThat(deadline.expired(clock)).isFalse();
        assertThat(deadline.remaining(clock)).isCloseTo(10.0, within(1e-6));
    }

    @Test
    void deadlineIsHashableAndComparableByValue() {
        assertThat(new Deadline(5)).isEqualTo(new Deadline(5));
        assertThat(new HashSet<>(List.of(new Deadline(5), new Deadline(5)))).hasSize(1);
    }
}
