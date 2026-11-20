package io.github.rkaushikdev.trammelworks.redact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Maps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedactRulesTest {

    static final String DRIVER = "driver=(\\d{4,10})";

    static Rule rule() {
        return new Rule("driver_id", DRIVER);
    }

    @Test
    void aRuleNeedsASaneName() {
        assertThatThrownBy(() -> new Rule("driver id", DRIVER))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("malformed rule name");
    }

    @Test
    void aRuleNeedsAPatternThatCompiles() {
        assertThatThrownBy(() -> new Rule("driver_id", "(unclosed"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("does not compile");
    }

    @Test
    void severalGroupsNeedOneNamedSecret() {
        assertThatThrownBy(() -> new Rule("driver_id", "(a)(b)"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must name one 'secret'");
    }

    @Test
    void aNamedSecretGroupIsAcceptedAmongOthers() {
        Rule built = new Rule("driver_id", "(driver)=(?P<secret>\\d+)");
        assertThat(built.pattern().chars().filter(c -> c == '(').count()).isEqualTo(2);
    }

    @Test
    void aDropRuleMayNotKeepCharacters() {
        assertThatThrownBy(() -> new Rule("driver_id", DRIVER, Action.DROP, 2, 0, Rule.DEFAULT_MARKER))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("cannot keep characters");
    }

    @Test
    void aNegativeKeepIsRefused() {
        assertThatThrownBy(() -> Rule.mask("driver_id", DRIVER, 0, -1))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("negative number of characters");
    }

    @Test
    void aDropRuleReplacesTheGroupOnly() {
        Rules.Applied found = Rules.applyRule(rule(), "auth driver=88213 pump=3");
        assertThat(found.text()).isEqualTo("auth driver=[redacted] pump=3");
        assertThat(found.hits()).extracting(RuleHit::rule).containsExactly("driver_id");
    }

    @Test
    void theHitRecordsWhatWasRemoved() {
        assertThat(Rules.applyRule(rule(), "auth driver=88213 pump=3")
                        .hits()
                        .get(0)
                        .text())
                .isEqualTo("88213");
    }

    @Test
    void theHitSpanPointsAtTheOriginalText() {
        String line = "auth driver=88213 pump=3";
        RuleHit hit = Rules.applyRule(rule(), line).hits().get(0);
        assertThat(line.substring(hit.start(), hit.end())).isEqualTo("88213");
    }

    @Test
    void aWholeMatchIsUsedWhenThereIsNoGroup() {
        Rule built = new Rule("reg", "[A-Z]{2}\\d{2} [A-Z]{3}");
        assertThat(Rules.applyRule(built, "vehicle AB12 CDE arrived").text()).isEqualTo("vehicle [redacted] arrived");
    }

    @Test
    void theNamedGroupWinsOverTheFirstOne() {
        Rule built = new Rule("loyalty", "(card)=(?P<secret>\\d+)");
        assertThat(Rules.applyRule(built, "loyalty card=998877").text()).isEqualTo("loyalty card=[redacted]");
    }

    @Test
    void aMaskRuleKeepsTheEnds() {
        Rule built = Rule.mask("driver_id", DRIVER, 2, 2);
        assertThat(Rules.applyRule(built, "auth driver=88213").text()).isEqualTo("auth driver=88*13");
    }

    @Test
    void aMaskThatWouldKeepEverythingMasksItAll() {
        Rule built = Rule.mask("driver_id", DRIVER, 4, 4);
        assertThat(Rules.applyRule(built, "auth driver=8821").text()).isEqualTo("auth driver=****");
    }

    @Test
    void aMaskWithNoKeepsHidesEverything() {
        Rule built = Rule.mask("driver_id", DRIVER, 0, 0);
        assertThat(Rules.applyRule(built, "auth driver=88213").text()).isEqualTo("auth driver=*****");
    }

    @Test
    void aCustomMarkerIsUsed() {
        Rule built = Rule.drop("driver_id", DRIVER, "<gone>");
        assertThat(Rules.applyRule(built, "auth driver=88213").text()).isEqualTo("auth driver=<gone>");
    }

    @Test
    void everyOccurrenceIsReplaced() {
        Rules.Applied found = Rules.applyRule(rule(), "driver=1111 then driver=2222");
        assertThat(found.text()).isEqualTo("driver=[redacted] then driver=[redacted]");
        assertThat(found.hits()).hasSize(2);
    }

    @Test
    void aLineWithNoMatchIsUntouched() {
        Rules.Applied found = Rules.applyRule(rule(), "nozzle 1 lifted");
        assertThat(found.text()).isEqualTo("nozzle 1 lifted");
        assertThat(found.hits()).isEmpty();
    }

    @Test
    void applyingARuleTwiceIsStable() {
        String once = Rules.applyRule(rule(), "driver=88213").text();
        Rules.Applied twice = Rules.applyRule(rule(), once);
        assertThat(twice.text()).isEqualTo(once);
        assertThat(twice.hits()).isEmpty();
    }

    @Test
    void ruleFromMappingBuildsADropRule() {
        Rule built = Rules.ruleFromMapping("driver_id", Map.of("pattern", "driver=(\\d+)"));
        assertThat(built.action()).isSameAs(Action.DROP);
    }

    @Test
    void ruleFromMappingBuildsAMaskRule() {
        Map<String, Object> raw = Maps.of("pattern", "(\\d{11})", "action", "mask", "keep_last", 4);
        Rule built = Rules.ruleFromMapping("loyalty", raw);
        assertThat(built.action()).isSameAs(Action.MASK);
        assertThat(built.keepLast()).isEqualTo(4);
    }

    @Test
    void ruleFromMappingNeedsAPattern() {
        assertThatThrownBy(() -> Rules.ruleFromMapping("driver_id", Map.of("action", "drop")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("no pattern");
    }

    @Test
    void ruleFromMappingRejectsAnUnknownKey() {
        assertThatThrownBy(() -> Rules.ruleFromMapping("driver_id", Map.of("pattern", "x", "keep", 2)))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown rule key");
    }

    @Test
    void ruleFromMappingRejectsAnUnknownAction() {
        assertThatThrownBy(() -> Rules.ruleFromMapping("driver_id", Map.of("pattern", "x", "action", "shred")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown rule action");
    }

    @Test
    void rulesFromMappingKeepsTheWrittenOrder() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("loyalty", Map.of("pattern", "(\\d{11})"));
        raw.put("driver_id", Map.of("pattern", "driver=(\\d+)"));
        assertThat(Rules.rulesFromMapping(raw)).extracting(Rule::name).containsExactly("loyalty", "driver_id");
    }

    @Test
    void rulesFromMappingRejectsANonMapping() {
        assertThatThrownBy(() -> Rules.rulesFromMapping(Map.of("loyalty", "\\d+")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be a mapping");
    }

    @Test
    void anEmptyRuleTableIsFine() {
        assertThat(Rules.rulesFromMapping(Map.of())).isEqualTo(List.of());
    }
}
