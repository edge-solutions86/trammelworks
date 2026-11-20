package io.github.rkaushikdev.trammelworks.spool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Maps;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpoolConfigTest {

    static SpoolSettings settings(long budget, long segment, double reserve) {
        return SpoolSettings.builder()
                .budgetBytes(budget)
                .segmentBytes(segment)
                .reserveRatio(reserve)
                .build();
    }

    @Test
    void theDefaultsAreConsistent() {
        SpoolSettings built = new SpoolSettings();
        assertThat(built.budget().usableBytes()).isGreaterThan(built.segmentBytes());
    }

    @Test
    void theBudgetObjectCarriesTheReserve() {
        SpoolSettings built = settings(1000, 100, 0.2);
        assertThat(built.budget().reserveBytes()).isEqualTo(200);
        assertThat(built.budget().usableBytes()).isEqualTo(800);
    }

    @Test
    void aSegmentLargerThanUsableIsRefused() {
        assertThatThrownBy(() -> settings(1000, 900, 0.2))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("does not fit inside the usable budget");
    }

    @Test
    void theRefusalNamesBothSizes() {
        ConfigError caught = catchThrowableOfType(ConfigError.class, () -> settings(1024, 1024, 0.5));
        assertThat(caught.detail()).isPresent();
        assertThat(caught.detail().get()).contains("segment").contains("usable");
    }

    @Test
    void aSegmentExactlyTheUsableSizeIsAllowed() {
        assertThat(settings(1000, 800, 0.2).segmentBytes()).isNotZero();
    }

    @Test
    void aZeroSegmentIsRefused() {
        assertThatThrownBy(() -> SpoolSettings.builder().segmentBytes(0).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("segment size must be positive");
    }

    @Test
    void aZeroBudgetIsRefused() {
        assertThatThrownBy(() -> SpoolSettings.builder().budgetBytes(0).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("budget must be positive");
    }

    @Test
    void aReserveOfOneIsRefused() {
        assertThatThrownBy(() -> SpoolSettings.builder().reserveRatio(1.0).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("reserve ratio must be in");
    }

    @Test
    void aNegativeSyncIntervalIsRefused() {
        assertThatThrownBy(() -> SpoolSettings.builder().syncEvery(-1).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("sync_every cannot be negative");
    }

    @Test
    void theDefaultPriorityTableIsUsedWhenNoneIsGiven() {
        assertThat(new SpoolSettings().table().shipOrder().get(0)).isEqualTo("payment");
    }

    @Test
    void thePathIsAPath() {
        assertThat(SpoolSettings.at("/var/lib/x").path().getFileName()).hasToString("x");
    }

    @Test
    void describeReadsInHumanUnits() {
        String line = SpoolSettings.builder()
                .budgetBytes(1024 * 1024)
                .segmentBytes(64 * 1024)
                .build()
                .describe();
        assertThat(line).contains("1MiB").contains("64KiB");
    }

    @Test
    void theMappingBuilderReadsWrittenSizes() {
        SpoolSettings built = SpoolSettings.spoolSettingsFromMapping(
                Maps.of("budget", "1GiB", "segment", "16MiB", "reserve_ratio", 0.05));
        assertThat(built.budgetBytes()).isEqualTo(1073741824L);
        assertThat(built.segmentBytes()).isEqualTo(16777216L);
        assertThat(built.reserveRatio()).isEqualTo(0.05);
    }

    @Test
    void anEmptySectionGivesTheDefaults() {
        assertThat(SpoolSettings.spoolSettingsFromMapping(Map.of())).isEqualTo(new SpoolSettings());
    }

    @Test
    void theMappingBuilderRejectsAnUnknownKey() {
        assertThatThrownBy(() -> SpoolSettings.spoolSettingsFromMapping(Map.of("budgett", "1GiB")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown spool key");
    }

    @Test
    void theMappingBuilderReadsAPrioritySection() {
        SpoolSettings built = SpoolSettings.spoolSettingsFromMapping(
                Map.of("priority", Map.of("units", Map.of("payment-kernel", "payment"))));
        assertThat(built.table().classFor("payment-kernel").name()).isEqualTo("payment");
    }

    @Test
    void aPrioritySectionThatIsNotAMappingIsRefused() {
        assertThatThrownBy(() -> SpoolSettings.spoolSettingsFromMapping(Map.of("priority", List.of("payment"))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("priority section must be a mapping");
    }

    @Test
    void compressionCanBeTurnedOff() {
        assertThat(SpoolSettings.spoolSettingsFromMapping(Map.of("compress", false))
                        .compress())
                .isFalse();
    }

    @Test
    void anInconsistentSectionIsCaughtAtBuildTime() {
        assertThatThrownBy(() -> SpoolSettings.spoolSettingsFromMapping(Map.of("budget", "1MiB", "segment", "1MiB")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("does not fit");
    }

    @Test
    void aUnitInAnUnknownClassIsCaughtAtBuildTime() {
        assertThatThrownBy(() -> SpoolSettings.spoolSettingsFromMapping(
                        Map.of("priority", Map.of("units", Map.of("pump-bridge", "urgent")))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown class");
    }
}
