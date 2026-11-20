package io.github.rkaushikdev.trammelworks.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArchiveConfigTest {

    @Test
    void theDefaultsAreOn() {
        ArchiveSettings built = new ArchiveSettings();
        assertThat(built.enabled()).isTrue();
        assertThat(built.file().getFileName()).hasToString("records.sqlite3");
    }

    @Test
    void theArchiveCanBeTurnedOff() {
        assertThat(ArchiveSettings.builder().enabled(false).build().enabled()).isFalse();
    }

    @Test
    void aZeroDeleteCapIsRefused() {
        assertThatThrownBy(() -> ArchiveSettings.builder().maxDeletes(0).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void aZeroSizeCapIsRefused() {
        assertThatThrownBy(() -> ArchiveSettings.builder().maxBytes(0).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("max_bytes must be positive");
    }

    @Test
    void aZeroSweepIntervalIsRefused() {
        assertThatThrownBy(() ->
                        ArchiveSettings.builder().sweepEvery(Duration.ZERO).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("sweep_every must be positive");
    }

    @Test
    void anArchiveBesideTheSpoolIsFine() {
        assertThatCode(() -> ArchiveSettings.at("/var/lib/stanchion/archive/records.sqlite3")
                        .checkAgainstSpool("/var/lib/stanchion/spool"))
                .doesNotThrowAnyException();
    }

    @Test
    void anArchiveInsideTheSpoolIsRefused() {
        ArchiveSettings built = ArchiveSettings.at("/var/lib/stanchion/spool/records.sqlite3");
        assertThatThrownBy(() -> built.checkAgainstSpool("/var/lib/stanchion/spool"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must not live inside the spool");
    }

    @Test
    void anArchiveDeeperInsideTheSpoolIsRefused() {
        ArchiveSettings built = ArchiveSettings.at("/var/lib/stanchion/spool/deep/records.sqlite3");
        assertThatThrownBy(() -> built.checkAgainstSpool("/var/lib/stanchion/spool"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must not live inside the spool");
    }

    @Test
    void describeNamesThePathAndTheWindows() {
        String line = new ArchiveSettings().describe();
        assertThat(line).contains("records.sqlite3").contains("payment 180d");
    }

    @Test
    void aDisabledArchiveSaysSo() {
        assertThat(ArchiveSettings.builder().enabled(false).build().describe()).isEqualTo("archive off");
    }

    @Test
    void theMappingBuilderReadsTheSection() {
        Map<String, Object> raw = Maps.of(
                "path", "/data/archive.sqlite3",
                "max_bytes", "1GiB",
                "sweep_every", "2h",
                "retention", Map.of("chatter", "3d"));
        ArchiveSettings built = ArchiveSettings.archiveSettingsFromMapping(raw);
        assertThat(built.path()).isEqualTo("/data/archive.sqlite3");
        assertThat(built.maxBytes()).isEqualTo(1073741824L);
        assertThat(built.sweepEvery()).isEqualTo(Duration.ofHours(2));
        assertThat(built.retention().windowFor("chatter")).isEqualTo(Duration.ofDays(3));
    }

    @Test
    void anEmptySectionGivesTheDefaults() {
        assertThat(ArchiveSettings.archiveSettingsFromMapping(Map.of())).isEqualTo(new ArchiveSettings());
    }

    @Test
    void theMappingBuilderRejectsAnUnknownKey() {
        assertThatThrownBy(() -> ArchiveSettings.archiveSettingsFromMapping(Map.of("pth", "/data/x")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown archive key");
    }

    @Test
    void theMappingBuilderNeedsRetentionToBeAMapping() {
        assertThatThrownBy(() -> ArchiveSettings.archiveSettingsFromMapping(Map.of("retention", List.of("chatter"))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("retention section must be a mapping");
    }

    @Test
    void theMappingBuilderStillEnforcesTheSchemeFloor() {
        assertThatThrownBy(
                        () -> ArchiveSettings.archiveSettingsFromMapping(Map.of("retention", Map.of("payment", "1d"))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("below the scheme floor");
    }

    @Test
    void aSiteCarriesAnArchiveSection() {
        assertThat(SiteConfig.of("US-TX-0417").archive().enabled()).isTrue();
    }

    @Test
    void aSiteRefusesAnArchiveInsideItsSpool() {
        assertThatThrownBy(() -> SiteConfig.builder("US-TX-0417")
                        .spool(SpoolSettings.at("/var/lib/stanchion/spool"))
                        .archive(ArchiveSettings.at("/var/lib/stanchion/spool/records.sqlite3"))
                        .build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must not live inside the spool");
    }

    @Test
    void theArchiveSectionIsReadFromAConfig() {
        SiteConfig built = SiteConfig.siteFromMapping(
                Map.of("site", "US-TX-0417", "archive", Maps.of("path", "/data/a.sqlite3", "enabled", false)));
        assertThat(built.archive().enabled()).isFalse();
        assertThat(built.archive().path()).isEqualTo("/data/a.sqlite3");
    }

    @Test
    void theArchiveSectionAppearsInAsDict() {
        Map<?, ?> archive = (Map<?, ?>) SiteConfig.of("US-TX-0417").asDict().get("archive");
        assertThat(archive.get("enabled")).isEqualTo(true);
        assertThat((String) archive.get("path")).endsWith("records.sqlite3");
    }

    @Test
    void anUnknownTopLevelSectionIsStillRefused() {
        assertThatThrownBy(() -> SiteConfig.siteFromMapping(Map.of("site", "US-TX-0417", "archiv", Map.of())))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown config key");
    }
}
