package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.rkaushikdev.trammelworks.archive.RetentionPolicy;
import io.github.rkaushikdev.trammelworks.ingest.MultilineRule;
import io.github.rkaushikdev.trammelworks.redact.Rule;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The shipped example has to be a config this build actually accepts. An example that no longer
 * parses is worse than none, because it is the first thing an engineer copies onto a box, so it is
 * loaded here rather than admired.
 */
class ExampleConfigTest {

    static final Path EXAMPLE = Path.of("examples", "site.yaml");

    Map<String, Object> raw;
    SiteConfig config;

    @BeforeEach
    void setUp() throws IOException {
        raw = ConfigFile.parseYaml(Files.readString(EXAMPLE, StandardCharsets.UTF_8));
        config = SiteConfig.siteFromMapping(raw);
    }

    @Test
    void theExampleIsThere() {
        assertThat(EXAMPLE).exists();
    }

    @Test
    void theExampleLoadsFromDisk() {
        assertThat(ConfigFile.loadSiteConfig(EXAMPLE, Map.of()).site()).isEqualTo("US-TX-0417");
    }

    @Test
    void theExampleNamesOnlySectionsThisBuildKnows() {
        assertThat(SiteConfig.SECTIONS).containsAll(raw.keySet());
    }

    @Test
    void theExampleWritesEverySection() {
        // A worked example that leaves half the file out teaches half the file.
        assertThat(raw.keySet()).isEqualTo(SiteConfig.SECTIONS);
    }

    @Test
    void theIngestSectionComesThrough() {
        assertThat(config.ingest().logRoot()).isEqualTo("/var/log/containers");
        assertThat(config.ingest().pollInterval()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void theMultilineRulesComeThrough() {
        Set<String> units =
                config.ingest().multiline().stream().map(MultilineRule::unit).collect(Collectors.toSet());
        assertThat(units).isEqualTo(Set.of("payment-kernel", "pump-bridge"));
    }

    @Test
    void theSpoolBudgetIsReadInBinaryUnits() {
        assertThat(config.spool().budgetBytes()).isEqualTo(512L * 1024 * 1024);
        assertThat(config.spool().segmentBytes()).isEqualTo(8L * 1024 * 1024);
    }

    @Test
    void theAllowanceIsReadInDecimalUnits() {
        // The plan is sold in decimal gigabytes and the disk is formatted in binary ones. Reading
        // either as the other is off by about five percent on the one number that costs money.
        assertThat(config.ship().monthlyAllowanceBytes()).isEqualTo(2_000_000_000L);
    }

    @Test
    void thePriorityTableAssignsTheUnitsItNames() {
        PriorityTable table = config.spool().table();
        assertThat(table.classFor("payment-kernel").name()).isEqualTo("payment");
        assertThat(table.classFor("price-sign").name()).isEqualTo("chatter");
        assertThat(table.classFor("tank-gauge").name()).isEqualTo("standard");
    }

    @Test
    void theRetentionWindowsComeThrough() {
        RetentionPolicy policy = config.archive().retention();
        assertThat(policy.windowFor("payment")).isEqualTo(Duration.ofDays(180));
        assertThat(policy.windowFor("chatter")).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void redactionIsOnWithSiteRules() {
        assertThat(config.redact().enabled()).isTrue();
        assertThat(config.redact().rules().stream().map(Rule::name).collect(Collectors.toSet()))
                .isEqualTo(Set.of("driver_id", "fleet_card_ref"));
    }

    @Test
    void theExampleNamesASigningKeyWithoutHoldingOne() throws IOException {
        // The path is in the file. The secret never is.
        assertThat(config.ship().signingKeyFile()).isEqualTo("/etc/stanchion/site.key");
        assertThat(Files.readString(EXAMPLE, StandardCharsets.UTF_8)).doesNotContain("BEGIN");
    }

    @Test
    void theExampleHoldsNoSecretLookingValues() throws IOException {
        String body = Files.readString(EXAMPLE, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        for (String word : List.of("password", "secret:", "token:", "api_key")) {
            assertThat(body).doesNotContain(word);
        }
    }

    @Test
    void theArchiveDoesNotTrimByDefault() {
        // Deleting records retention would have kept is opt in, and the example has to show the
        // safe setting.
        assertThat(config.archive().trimToCap()).isFalse();
    }

    @Test
    void theExampleRoundTripsThroughTheDumper() {
        SiteConfig again = SiteConfig.siteFromMapping(ConfigFile.parseYaml(ConfigFile.dumpSiteConfig(config)));
        assertThat(again.site()).isEqualTo(config.site());
        assertThat(again.spool().budgetBytes()).isEqualTo(config.spool().budgetBytes());
        assertThat(again.ship().monthlyAllowanceBytes()).isEqualTo(config.ship().monthlyAllowanceBytes());
    }

    @Test
    void theArchiveIsNotInsideTheSpool() {
        assertThatCode(() -> config.archive().checkAgainstSpool(config.spool().directory()))
                .doesNotThrowAnyException();
    }

    @Test
    void theExampleEndpointAndBudgetsPassTheDoctor() {
        // Only the checks that read the config: several of the others probe a directory by
        // creating it, which is right on a box being commissioned and wrong for /var/lib on
        // whatever machine runs the suite.
        DoctorReport report = new DoctorReport();
        Doctor.checkShip(config, report);
        Doctor.checkSpace(config, report);
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void theExampleSigningIsConfiguredEvenThoughTheKeyIsElsewhere() {
        // The key is a real path this machine does not have, so the check fails here and would
        // pass on a commissioned site. What matters is that the example asks for signing at all.
        assertThat(config.ship().signs()).isTrue();
        DoctorReport report = new DoctorReport();
        Doctor.checkSigning(config, report);
        assertThat(report.findings()).extracting(Finding::check).containsExactly("signing");
    }

    @Test
    void theEndpointIsNotThePlaceholder() {
        assertThat(config.ship().endpoint()).doesNotContain("invalid");
    }

    @Test
    void theExampleLabelsTheSite() {
        assertThat(config.labels()).isEqualTo(Map.of("estate", "southwest", "brand", "forecourt-one"));
    }
}
