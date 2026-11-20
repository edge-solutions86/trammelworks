package io.github.rkaushikdev.trammelworks.spool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Maps;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SpoolPriorityTest {

    @Test
    void theBuiltinClassesCoverTheThreeCases() {
        Set<String> names =
                PriorityTable.BUILTIN_CLASSES.stream().map(PriorityClass::name).collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder("payment", "standard", "chatter");
    }

    @Test
    void paymentOutranksEverything() {
        assertThat(new PriorityTable().shipOrder().get(0)).isEqualTo("payment");
    }

    @Test
    void paymentIsProtectedByDefault() {
        assertThat(new PriorityTable().protectedClasses()).isEqualTo(Set.of("payment"));
    }

    @Test
    void anUnassignedUnitLandsInTheDefault() {
        assertThat(new PriorityTable().classFor("pump-bridge").name()).isEqualTo("standard");
    }

    @Test
    void anAssignedUnitLandsInItsClass() {
        PriorityTable table = new PriorityTable(Map.of("payment-kernel", "payment"));
        assertThat(table.classFor("payment-kernel").name()).isEqualTo("payment");
    }

    @Test
    void assignmentsAreNormalized() {
        PriorityTable table = new PriorityTable(Map.of("Payment-Kernel", "payment"));
        assertThat(table.classFor("payment-kernel").name()).isEqualTo("payment");
    }

    @Test
    void anAssignmentToAnUnknownClassIsRefused() {
        assertThatThrownBy(() -> new PriorityTable(Map.of("payment-kernel", "urgent")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown class");
    }

    @Test
    void aDefaultThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> new PriorityTable(PriorityTable.BUILTIN_CLASSES, Map.of(), "urgent"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("default class is not defined");
    }

    @Test
    void twoClassesMayNotShareAName() {
        List<PriorityClass> twice = List.of(new PriorityClass("a", 1), new PriorityClass("a", 2));
        assertThatThrownBy(() -> new PriorityTable(twice, Map.of(), "a"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("share a name");
    }

    @Test
    void twoClassesMayNotShareAWeight() {
        List<PriorityClass> twice = List.of(new PriorityClass("a", 1), new PriorityClass("b", 1));
        assertThatThrownBy(() -> new PriorityTable(twice, Map.of(), "a"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("share a weight");
    }

    @Test
    void aNegativeWeightIsRefused() {
        assertThatThrownBy(() -> new PriorityClass("a", -1))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void aMalformedClassNameIsRefused() {
        assertThatThrownBy(() -> new PriorityClass("Payment", 1)).hasMessageContaining("malformed priority class");
    }

    @Test
    void shipOrderRunsHighestWeightFirst() {
        assertThat(new PriorityTable().shipOrder()).containsExactly("payment", "standard", "chatter");
    }

    @Test
    void evictOrderRunsLowestWeightFirst() {
        assertThat(new PriorityTable().evictOrder()).containsExactly("chatter", "standard");
    }

    @Test
    void aProtectedClassIsAbsentFromTheEvictOrder() {
        assertThat(new PriorityTable().evictOrder()).doesNotContain("payment");
    }

    @Test
    void theTwoOrdersAreOppositesOverWhatTheyShare() {
        PriorityTable table = new PriorityTable();
        List<String> evictable = new ArrayList<>(
                table.shipOrder().stream().filter(table.evictOrder()::contains).toList());
        Collections.reverse(evictable);
        assertThat(evictable).isEqualTo(table.evictOrder());
    }

    @Test
    void byNameFindsAClass() {
        assertThat(new PriorityTable().byName("chatter").weight()).isEqualTo(10);
    }

    @Test
    void byNameRefusesAnUnknownClass() {
        assertThatThrownBy(() -> new PriorityTable().byName("urgent"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown priority class");
    }

    @Test
    void theMappingBuilderKeepsTheBuiltinsWhenNoneAreGiven() {
        assertThat(PriorityTable.tableFromMapping(Map.of()).classes()).isEqualTo(PriorityTable.BUILTIN_CLASSES);
    }

    @Test
    void theMappingBuilderReadsAssignments() {
        PriorityTable table = PriorityTable.tableFromMapping(Map.of("units", Map.of("payment-kernel", "payment")));
        assertThat(table.classFor("payment-kernel").name()).isEqualTo("payment");
    }

    @Test
    void theMappingBuilderReadsCustomClasses() {
        Map<String, Object> classes = new LinkedHashMap<>();
        classes.put("urgent", Map.of("weight", 200, "protected", true));
        classes.put("normal", Map.of("weight", 50));
        PriorityTable table = PriorityTable.tableFromMapping(Maps.of("classes", classes, "default", "normal"));
        assertThat(table.shipOrder()).containsExactly("urgent", "normal");
        assertThat(table.protectedClasses()).isEqualTo(Set.of("urgent"));
    }

    @Test
    void aClassMayBeWrittenAsABareWeight() {
        PriorityTable table = PriorityTable.tableFromMapping(Maps.of("classes", Map.of("only", 5), "default", "only"));
        assertThat(table.byName("only").weight()).isEqualTo(5);
    }

    @Test
    void theMappingBuilderRejectsAnUnknownKey() {
        assertThatThrownBy(() -> PriorityTable.tableFromMapping(Map.of("clases", Map.of())))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown priority key");
    }

    @Test
    void theMappingBuilderNeedsClassesToBeAMapping() {
        assertThatThrownBy(() -> PriorityTable.tableFromMapping(Map.of("classes", List.of("payment"))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("classes must be a mapping");
    }

    @Test
    void theMappingBuilderNeedsUnitsToBeAMapping() {
        assertThatThrownBy(() -> PriorityTable.tableFromMapping(Map.of("units", List.of("payment-kernel"))))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("assignments must be a mapping");
    }

    @Test
    void oneSettingDecidesShipAndEvictTogether() {
        PriorityTable table = new PriorityTable(Map.of("payment-kernel", "payment", "price-sign", "chatter"));
        PriorityClass payment = table.classFor("payment-kernel");
        PriorityClass sign = table.classFor("price-sign");
        assertThat(payment.weight()).isGreaterThan(sign.weight());
        assertThat(table.shipOrder().indexOf("payment"))
                .isLessThan(table.shipOrder().indexOf("chatter"));
        assertThat(table.evictOrder().indexOf("chatter")).isZero();
    }
}
