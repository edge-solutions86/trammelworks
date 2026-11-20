package io.github.rkaushikdev.trammelworks.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.RetentionError;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArchiveRetentionTest {

    static final NanoTime NOW = NanoTime.parse("2025-09-01T12:00:00Z");

    static NanoTime at(String raw) {
        return NanoTime.parse(raw);
    }

    static RetentionPolicy chatterFor(int days) {
        return new RetentionPolicy(Map.of("chatter", Duration.ofDays(days)));
    }

    static Hold hold(String name) {
        return new Hold(name, "dispute", NOW);
    }

    static Hold hold(String from, String until) {
        return new Hold(
                "case-41",
                "dispute",
                NOW,
                Optional.ofNullable(from).map(NanoTime::parse),
                Optional.ofNullable(until).map(NanoTime::parse));
    }

    @Test
    void paymentHasTheSchemeFloor() {
        assertThat(RetentionPolicy.floorFor("payment")).isEqualTo(Duration.ofDays(180));
    }

    @Test
    void anUnknownClassHasNoFloor() {
        assertThat(RetentionPolicy.floorFor("brand-new")).isEqualTo(Duration.ZERO);
    }

    @Test
    void theDefaultsSatisfyEveryFloor() {
        RetentionPolicy policy = new RetentionPolicy();
        for (String klass : RetentionPolicy.CLASS_FLOORS.keySet()) {
            assertThat(policy.windowFor(klass)).isGreaterThanOrEqualTo(RetentionPolicy.floorFor(klass));
        }
    }

    @Test
    void aWindowBelowTheFloorIsRefused() {
        assertThatThrownBy(() -> new RetentionPolicy(Map.of("payment", Duration.ofDays(30))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("below the scheme floor");
    }

    @Test
    void theRefusalNamesBothDurations() {
        ConfigError caught = catchThrowableOfType(
                ConfigError.class, () -> new RetentionPolicy(Map.of("payment", Duration.ofDays(30))));
        assertThat(caught.detail())
                .hasValueSatisfying(detail -> assertThat(detail).contains("30d").contains("180d"));
    }

    @Test
    void aNegativeWindowIsRefused() {
        assertThatThrownBy(() -> new RetentionPolicy(Map.of("chatter", Duration.ofDays(-1))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void aLongerWindowThanTheFloorIsAllowed() {
        assertThat(new RetentionPolicy(Map.of("payment", Duration.ofDays(365))).windowFor("payment"))
                .isEqualTo(Duration.ofDays(365));
    }

    @Test
    void aClassWithNoWindowStillGetsItsFloor() {
        assertThat(chatterFor(1).windowFor("payment")).isEqualTo(Duration.ofDays(180));
    }

    @Test
    void theCutoffIsTheWindowBackFromNow() {
        assertThat(chatterFor(7).cutoffFor("chatter", NOW)).isEqualTo(at("2025-08-25T12:00:00Z"));
    }

    @Test
    void describeReadsInHumanUnits() {
        assertThat(new RetentionPolicy().describe()).contains("payment 180d").contains("chatter 7d");
    }

    @Test
    void aHoldNeedsAName() {
        assertThatThrownBy(() -> new Hold("  ", "dispute", NOW))
                .isInstanceOf(RetentionError.class)
                .hasMessageContaining("needs a name");
    }

    @Test
    void aHoldCannotEndBeforeItStarts() {
        assertThatThrownBy(() -> hold("2025-09-01T00:00:00Z", "2025-08-01T00:00:00Z"))
                .isInstanceOf(RetentionError.class)
                .hasMessageContaining("cannot end before it starts");
    }

    @Test
    void anOpenEndedHoldCoversEverything() {
        Hold found = hold("case-41");
        assertThat(found.openEnded()).isTrue();
        assertThat(found.covers(at("2020-01-01T00:00:00Z"))).isTrue();
        assertThat(found.covers(at("2030-01-01T00:00:00Z"))).isTrue();
    }

    @Test
    void aWindowedHoldCoversOnlyItsWindow() {
        Hold found = hold("2025-03-01T00:00:00Z", "2025-03-31T23:59:59Z");
        assertThat(found.covers(at("2025-03-15T00:00:00Z"))).isTrue();
        assertThat(found.covers(at("2025-04-15T00:00:00Z"))).isFalse();
        assertThat(found.covers(at("2025-02-15T00:00:00Z"))).isFalse();
    }

    @Test
    void aHoldWithOnlyAStartRunsForwards() {
        Hold found = hold("2025-03-01T00:00:00Z", null);
        assertThat(found.covers(at("2030-01-01T00:00:00Z"))).isTrue();
        assertThat(found.covers(at("2020-01-01T00:00:00Z"))).isFalse();
    }

    @Test
    void aRecentRecordIsKept() {
        RetentionDecision found =
                RetentionPolicy.decide(chatterFor(7), List.of(), "chatter", at("2025-08-30T12:00:00Z"), NOW);
        assertThat(found.deletable()).isFalse();
        assertThat(found.reason()).isEqualTo("inside the window");
    }

    @Test
    void anOldRecordMayGo() {
        assertThat(RetentionPolicy.decide(chatterFor(7), List.of(), "chatter", at("2025-08-01T12:00:00Z"), NOW)
                        .deletable())
                .isTrue();
    }

    @Test
    void aRecordExactlyOnTheCutoffIsKept() {
        assertThat(RetentionPolicy.decide(chatterFor(7), List.of(), "chatter", at("2025-08-25T12:00:00Z"), NOW)
                        .deletable())
                .isFalse();
    }

    @Test
    void aHoldBeatsAnExpiredWindow() {
        RetentionDecision found = RetentionPolicy.decide(
                chatterFor(7), List.of(hold("case-41")), "chatter", at("2020-01-01T00:00:00Z"), NOW);
        assertThat(found.deletable()).isFalse();
        assertThat(found.reason()).isEqualTo("held");
        assertThat(found.hold()).isEqualTo("case-41");
    }

    @Test
    void aHoldThatDoesNotCoverTheRecordDoesNotSaveIt() {
        Hold january = hold("2025-01-01T00:00:00Z", "2025-01-31T00:00:00Z");
        assertThat(RetentionPolicy.decide(chatterFor(7), List.of(january), "chatter", at("2025-08-01T12:00:00Z"), NOW)
                        .deletable())
                .isTrue();
    }

    @Test
    void theFirstCoveringHoldIsNamed() {
        List<Hold> holds = List.of(new Hold("first", "a", NOW), new Hold("second", "b", NOW));
        assertThat(RetentionPolicy.decide(new RetentionPolicy(), holds, "chatter", at("2020-01-01T00:00:00Z"), NOW)
                        .hold())
                .isEqualTo("first");
    }

    @Test
    void thePaymentFloorKeepsARecordASiteWantedGone() {
        assertThat(RetentionPolicy.decide(chatterFor(1), List.of(), "payment", at("2025-06-01T12:00:00Z"), NOW)
                        .deletable())
                .isFalse();
    }

    @Test
    void theMappingBuilderReadsWrittenDurations() {
        RetentionPolicy policy = RetentionPolicy.policyFromMapping(Map.of("chatter", "3d", "payment", "200d"));
        assertThat(policy.windowFor("chatter")).isEqualTo(Duration.ofDays(3));
        assertThat(policy.windowFor("payment")).isEqualTo(Duration.ofDays(200));
    }

    @Test
    void theMappingBuilderKeepsTheDefaultsForAbsentClasses() {
        assertThat(RetentionPolicy.policyFromMapping(Map.of("chatter", "3d")).windowFor("payment"))
                .isEqualTo(Duration.ofDays(180));
    }

    @Test
    void anEmptySectionGivesTheDefaults() {
        assertThat(RetentionPolicy.policyFromMapping(Map.of())).isEqualTo(new RetentionPolicy());
    }

    @Test
    void theMappingBuilderStillEnforcesTheFloor() {
        assertThatThrownBy(() -> RetentionPolicy.policyFromMapping(Map.of("payment", "30d")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("below the scheme floor");
    }

    @Test
    void aWholeNumberOfDaysIsWrittenInDays() {
        assertThat(RetentionPolicy.formatRetention(Duration.ofDays(180))).isEqualTo("180d");
        assertThat(RetentionPolicy.formatRetention(Duration.ofDays(1))).isEqualTo("1d");
    }

    @Test
    void aPartDayFallsBackToTheGeneralForm() {
        assertThat(RetentionPolicy.formatRetention(Duration.ofHours(36))).isEqualTo("1d12h");
    }

    @Test
    void zeroIsWrittenAsZeroSeconds() {
        assertThat(RetentionPolicy.formatRetention(Duration.ZERO)).isEqualTo("0s");
    }
}
