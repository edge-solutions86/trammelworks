package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TelemetryTest {

    @ParameterizedTest
    @ValueSource(strings = {"fragments_read", "records", "spool_bytes_in_use", "a1_b2"})
    void checkNameAcceptsSnakeCase(String name) {
        assertThat(Registry.checkName(name)).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Fragments", "fragments-read", "_leading", "trailing_", "9lives", "", "double__score"})
    void checkNameRejectsAnythingElse(String name) {
        assertThatThrownBy(() -> Registry.checkName(name))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("malformed metric name");
    }

    @Test
    void counterStartsAtZero() {
        assertThat(new Counter("fragments_read").value()).isZero();
    }

    @Test
    void counterIncrementsByOneByDefault() {
        Counter counter = new Counter("fragments_read");
        counter.inc();
        counter.inc();
        assertThat(counter.value()).isEqualTo(2);
    }

    @Test
    void counterIncrementsByAnAmount() {
        Counter counter = new Counter("bytes_shipped");
        counter.inc(4096);
        assertThat(counter.value()).isEqualTo(4096);
    }

    @Test
    void counterAcceptsAZeroIncrement() {
        Counter counter = new Counter("bytes_shipped");
        counter.inc(0);
        assertThat(counter.value()).isZero();
    }

    @Test
    void counterRefusesToGoDown() {
        assertThatThrownBy(() -> new Counter("bytes_shipped").inc(-1))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("cannot decrease");
    }

    @Test
    void gaugeCanBeSetAndMoved() {
        Gauge gauge = new Gauge("spool_bytes");
        gauge.set(1000);
        gauge.add(-400);
        assertThat(gauge.value()).isEqualTo(600);
    }

    @Test
    void gaugeMayGoNegativeBecauseADeltaCanBeWrong() {
        Gauge gauge = new Gauge("spool_bytes");
        gauge.add(-5);
        assertThat(gauge.value()).isEqualTo(-5);
    }

    @Test
    void registryReturnsTheSameCounterForARepeatedName() {
        Registry registry = new Registry();
        Counter first = registry.counter("fragments_read");
        first.inc(3);
        assertThat(registry.counter("fragments_read")).isSameAs(first);
        assertThat(registry.counter("fragments_read").value()).isEqualTo(3);
    }

    @Test
    void registryReturnsTheSameGaugeForARepeatedName() {
        Registry registry = new Registry();
        assertThat(registry.gauge("spool_bytes")).isSameAs(registry.gauge("spool_bytes"));
    }

    @Test
    void registryWillNotLetANameBeBothKinds() {
        Registry registry = new Registry();
        registry.counter("records");
        assertThatThrownBy(() -> registry.gauge("records"))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("already a counter");
    }

    @Test
    void registryWillNotLetAGaugeBecomeACounter() {
        Registry registry = new Registry();
        registry.gauge("spool_bytes");
        assertThatThrownBy(() -> registry.counter("spool_bytes"))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("already a gauge");
    }

    @Test
    void registryValidatesNames() {
        assertThatThrownBy(() -> new Registry().counter("Fragments Read"))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("malformed metric name");
    }

    @Test
    void incrIsAShortcutForCounterInc() {
        Registry registry = new Registry();
        registry.incr("records");
        registry.incr("records", 4);
        assertThat(registry.snapshot().get("records")).isEqualTo(5L);
    }

    @Test
    void snapshotMergesBothKindsAndSorts() {
        Registry registry = new Registry();
        registry.incr("zebra_total", 1);
        registry.gauge("alpha_bytes").set(9);
        assertThat(registry.snapshot().keySet()).containsExactly("alpha_bytes", "zebra_total");
    }

    @Test
    void snapshotIsACopy() {
        Registry registry = new Registry();
        registry.incr("records");
        Map<String, Long> snap = registry.snapshot();
        snap.put("records", 99L);
        assertThat(registry.snapshot().get("records")).isEqualTo(1L);
    }

    @Test
    void renderIsOnePairPerLine() {
        Registry registry = new Registry();
        registry.incr("records", 2);
        registry.gauge("spool_bytes").set(4096);
        assertThat(registry.render()).isEqualTo("records 2\nspool_bytes 4096");
    }

    @Test
    void renderOfAnEmptyRegistryIsEmpty() {
        assertThat(new Registry().render()).isEmpty();
    }

    @Test
    void resetClearsEverything() {
        Registry registry = new Registry();
        registry.incr("records");
        registry.gauge("spool_bytes").set(1);
        registry.reset();
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void twoRegistriesDoNotShareState() {
        Registry left = new Registry();
        Registry right = new Registry();
        left.incr("records", 5);
        assertThat(right.snapshot()).isEmpty();
    }
}
