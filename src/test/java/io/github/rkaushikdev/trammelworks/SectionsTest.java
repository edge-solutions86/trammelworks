package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.ingest.MultilineRule;
import io.github.rkaushikdev.trammelworks.redact.RedactConfig;
import io.github.rkaushikdev.trammelworks.ship.ShipSettings;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SectionsTest {

    static final Set<String> KNOWN = Set.of("directory", "budget", "segment", "compress");

    static ConfigError refusal(Runnable call) {
        return catchThrowableOfType(ConfigError.class, call::run);
    }

    @Test
    void aSectionWithOnlyKnownKeysPasses() {
        Map<String, Object> raw = Maps.of("budget", "1GiB", "compress", true);
        assertThat(Sections.checkKeys(raw, KNOWN, "spool")).isSameAs(raw);
    }

    @Test
    void anEmptySectionPasses() {
        assertThat(Sections.checkKeys(Map.of(), KNOWN, "spool")).isEmpty();
    }

    @Test
    void anUnknownKeyIsRefused() {
        assertThatThrownBy(() -> Sections.checkKeys(Map.of("nonsense", 1), KNOWN, "spool"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown spool key");
    }

    @Test
    void theSectionIsNamedInTheMessage() {
        assertThatThrownBy(() -> Sections.checkKeys(Map.of("nonsense", 1), KNOWN, "archive"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown archive key");
    }

    @Test
    void aNearMissGetsASuggestion() {
        assertThatThrownBy(() -> Sections.checkKeys(Map.of("budgt", "1GiB"), KNOWN, "spool"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("did you mean budget");
    }

    @Test
    void aKeyThatIsNothingLikeOneGetsNoSuggestion() {
        ConfigError caught = refusal(() -> Sections.checkKeys(Map.of("xyzzy", 1), KNOWN, "spool"));
        assertThat(caught.getMessage()).doesNotContain("did you mean");
    }

    @Test
    void severalWrongKeysAreAllNamed() {
        ConfigError caught = refusal(() -> Sections.checkKeys(Maps.of("one", 1, "two", 2), KNOWN, "spool"));
        assertThat(caught.getMessage()).contains("one, two");
    }

    @Test
    void severalWrongKeysGetNoSuggestion() {
        // Guessing at one of several is worse than not guessing: the engineer
        // fixes the one that was named and hits the same error again.
        ConfigError caught = refusal(() -> Sections.checkKeys(Maps.of("budgt", 1, "segmnt", 2), KNOWN, "spool"));
        assertThat(caught.getMessage()).doesNotContain("did you mean");
    }

    @Test
    void theWrongKeysComeOutSorted() {
        ConfigError caught = refusal(() -> Sections.checkKeys(Maps.of("zeta", 1, "alpha", 2), KNOWN, "spool"));
        assertThat(caught.getMessage()).contains("alpha, zeta");
    }

    @Test
    void aSuggestionIsFoundForACloseName() {
        assertThat(Sections.suggest("comress", KNOWN)).isEqualTo("compress");
    }

    @Test
    void noSuggestionForADistantName() {
        assertThat(Sections.suggest("qqqq", KNOWN)).isEmpty();
    }

    @Test
    void aNestedBlockComesBack() {
        assertThat(Sections.section(Map.of("retention", Map.of("payment", "180d")), "retention", "archive"))
                .isEqualTo(Map.of("payment", "180d"));
    }

    @Test
    void aMissingBlockDefaultsToEmpty() {
        assertThat(Sections.section(Map.of(), "retention", "archive")).isEmpty();
    }

    @Test
    void aBlockWrittenWithNothingUnderItDefaultsToEmpty() {
        // This is what YAML gives for a key with an empty body, and it is how
        // somebody writes "defaults please".
        Map<String, Object> raw = new HashMap<>();
        raw.put("retention", null);
        assertThat(Sections.section(raw, "retention", "archive")).isEmpty();
    }

    @Test
    void aBlockThatIsNotABlockIsRefused() {
        assertThatThrownBy(() -> Sections.section(Map.of("retention", List.of(1, 2)), "retention", "archive"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("the retention section must be a mapping");
    }

    @Test
    void aRequiredKeyComesBack() {
        assertThat(Sections.require(Map.of("site", "US-TX-0417"), "site", "config"))
                .isEqualTo("US-TX-0417");
    }

    @Test
    void aMissingRequiredKeyIsRefused() {
        assertThatThrownBy(() -> Sections.require(Map.of(), "site", "config"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("config has no site");
    }

    @Test
    void aRequiredKeyCanKeepTheWordsItsSectionUsed() {
        assertThatThrownBy(() -> Sections.require(Map.of(), "site", "config", "site id"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("config has no site id");
    }

    @Test
    void theSiteConfigStillRefusesAnUnknownSection() {
        assertThatThrownBy(() -> SiteConfig.siteFromMapping(Map.of("site", "US-TX-0417", "nonsense", Map.of())))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown config key");
    }

    @Test
    void theSiteConfigSuggestsASection() {
        assertThatThrownBy(() -> SiteConfig.siteFromMapping(Map.of("site", "US-TX-0417", "archiv", Map.of())))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("did you mean archive");
    }

    @Test
    void aConfigWithNoSiteIsRefused() {
        assertThatThrownBy(() -> SiteConfig.siteFromMapping(Map.of("spool", Map.of())))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("config has no site");
    }

    @Test
    void aSectionWrittenEmptyStillBuilds() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("site", "US-TX-0417");
        raw.put("spool", null);
        raw.put("archive", null);
        assertThat(SiteConfig.siteFromMapping(raw).site()).isEqualTo("US-TX-0417");
    }

    @Test
    void theSpoolSectionStillRefusesAnUnknownKey() {
        assertThatThrownBy(() -> SpoolSettings.spoolSettingsFromMapping(Map.of("budgt", "1GiB")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown spool key");
    }

    @Test
    void theShipSectionStillRefusesAnUnknownKey() {
        assertThatThrownBy(() -> ShipSettings.shipSettingsFromMapping(Map.of("endpiont", "https://c.invalid/v1")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown ship key");
    }

    @Test
    void theArchiveSectionStillRefusesAnUnknownKey() {
        assertThatThrownBy(() -> ArchiveSettings.archiveSettingsFromMapping(Map.of("pth", "/var/lib/x")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown archive key");
    }

    @Test
    void theRedactSectionStillRefusesAnUnknownKey() {
        assertThatThrownBy(() -> RedactConfig.redactFromMapping(Map.of("enabld", true)))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown redact key");
    }

    @Test
    void theMultilineRulesStillRefuseAnUnknownKey() {
        assertThatThrownBy(() -> MultilineRule.rulesFromMapping(Map.of("pump-bridge", Map.of("strt", "^ERROR"))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown multiline key");
    }

    @Test
    void everySectionPhrasesTheRefusalTheSameWay() {
        // The point of one helper: an engineer reading the error at eleven at night
        // sees the same sentence whichever section they typed into.
        Map<String, Object> bad = Map.of("nonsense", 1);
        List<Consumer<Map<String, Object>>> builders = List.of(
                SpoolSettings::spoolSettingsFromMapping,
                ShipSettings::shipSettingsFromMapping,
                ArchiveSettings::archiveSettingsFromMapping,
                RedactConfig::redactFromMapping);
        List<String> messages = new ArrayList<>();
        for (Consumer<Map<String, Object>> builder : builders) {
            ConfigError caught = refusal(() -> builder.accept(bad));
            messages.add(caught.getMessage().split(" key")[0]);
        }
        assertThat(messages).containsExactly("unknown spool", "unknown ship", "unknown archive", "unknown redact");
    }

    @Test
    void thePriorityBlockMustBeAMapping() {
        assertThatThrownBy(() -> SpoolSettings.spoolSettingsFromMapping(Map.of("priority", List.of(1, 2))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("the priority section must be a mapping");
    }

    @Test
    void theFloorsBlockMustBeAMapping() {
        assertThatThrownBy(() -> ShipSettings.shipSettingsFromMapping(Map.of("floors", List.of(1, 2))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("the floors section must be a mapping");
    }

    @Test
    void theRulesBlockMustBeAMapping() {
        assertThatThrownBy(() -> RedactConfig.redactFromMapping(Map.of("rules", List.of(1, 2))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("the rules section must be a mapping");
    }
}
