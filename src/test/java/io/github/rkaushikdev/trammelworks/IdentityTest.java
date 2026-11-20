package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class IdentityTest {

    static final String FULL_ID = "9f".repeat(32);

    static LogRecord record() {
        return new LogRecord(
                "US-TX-0417",
                "payment-kernel",
                io.github.rkaushikdev.trammelworks.Stream.STDOUT,
                NanoTime.parse("2025-03-04T11:30:00Z"),
                "auth approved");
    }

    @ParameterizedTest
    @ValueSource(strings = {"US-TX-0417", "GB-LDN-0001", "de-by-9999"})
    void parseSiteAcceptsEstateFormat(String raw) {
        assertThat(Identity.parseSite(raw)).isEqualTo(raw.toUpperCase(Locale.ROOT));
    }

    @Test
    void parseSiteTrimsWhitespace() {
        assertThat(Identity.parseSite("  us-tx-0417\n")).isEqualTo("US-TX-0417");
    }

    @ParameterizedTest
    @ValueSource(strings = {"US-TX-417", "USA-TX-0417", "US_TX_0417", "US-TX-04170", "", "US-TX", "US-T-0417"})
    void parseSiteRejectsAnythingElse(String raw) {
        assertThatThrownBy(() -> Identity.parseSite(raw))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("malformed site id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pump-bridge", "tankgauge", "loyalty-sync-2"})
    void parseUnitAcceptsServiceNames(String raw) {
        assertThat(Identity.parseUnit(raw)).isEqualTo(raw);
    }

    @Test
    void parseUnitLowercases() {
        assertThat(Identity.parseUnit("Payment-Kernel")).isEqualTo("payment-kernel");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-leading", "trailing-", "double--dash", "9front", "has_score", ""})
    void parseUnitRejectsAnythingElse(String raw) {
        assertThatThrownBy(() -> Identity.parseUnit(raw))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("malformed unit name");
    }

    @Test
    void shortIdKeepsTwelveCharacters() {
        assertThat(Identity.shortId(FULL_ID)).isEqualTo("9f9f9f9f9f9f");
    }

    @Test
    void shortIdAcceptsUpperCaseHex() {
        assertThat(Identity.shortId(FULL_ID.toUpperCase(Locale.ROOT))).isEqualTo("9f9f9f9f9f9f");
    }

    static Stream<String> badIds() {
        return Stream.of("9f".repeat(31), "9f".repeat(33), "zz".repeat(32), "");
    }

    @ParameterizedTest
    @MethodSource("badIds")
    void shortIdRejectsAWrongLengthOrNonHex(String raw) {
        assertThatThrownBy(() -> Identity.shortId(raw))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("malformed container id");
    }

    @Test
    void unitRefNormalizesAllThreeFields() {
        UnitRef ref = new UnitRef(" us-tx-0417 ", "Pump-Bridge", FULL_ID.toUpperCase(Locale.ROOT));
        assertThat(ref.site()).isEqualTo("US-TX-0417");
        assertThat(ref.unit()).isEqualTo("pump-bridge");
        assertThat(ref.containerId()).isEqualTo("9f9f9f9f9f9f");
        assertThat(ref.attempt()).isZero();
    }

    @Test
    void unitRefRejectsANegativeAttempt() {
        assertThatThrownBy(() -> new UnitRef("US-TX-0417", "pump-bridge", FULL_ID, -1))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("negative restart attempt");
    }

    @Test
    void unitRefSlugIsPathSafe() {
        UnitRef ref = new UnitRef("US-TX-0417", "pump-bridge", FULL_ID, 4);
        assertThat(ref.slug()).isEqualTo("US-TX-0417_pump-bridge_4");
        assertThat(ref.slug()).doesNotContain("/");
    }

    @Test
    void unitRefStrShowsTheShortId() {
        UnitRef ref = new UnitRef("US-TX-0417", "pump-bridge", FULL_ID, 2);
        assertThat(ref.toString()).isEqualTo("US-TX-0417/pump-bridge#2(9f9f9f9f9f9f)");
    }

    @Test
    void unitRefOfTwoRestartsDiffers() {
        UnitRef first = new UnitRef("US-TX-0417", "pump-bridge", FULL_ID, 0);
        UnitRef second = new UnitRef("US-TX-0417", "pump-bridge", FULL_ID, 1);
        assertThat(first).isNotEqualTo(second);
        assertThat(first.slug()).isNotEqualTo(second.slug());
    }

    @Test
    void recordKeyIsStableAcrossCalls() {
        assertThat(Identity.recordKey(record())).isEqualTo(Identity.recordKey(record()));
    }

    @Test
    void recordKeyIsHexOfTheDeclaredWidth() {
        String key = Identity.recordKey(record());
        assertThat(key).hasSize(32);
        assertThat(new BigInteger(key, 16)).isNotNegative();
    }

    static Stream<UnaryOperator<LogRecord>> gradedFields() {
        return Stream.of(
                found -> found.withSite("US-TX-0418"),
                found -> found.withUnit("tank-gauge"),
                found -> found.withStream(io.github.rkaushikdev.trammelworks.Stream.STDERR),
                found -> found.withMessage("auth declined"),
                found -> found.withObservedAt(NanoTime.parse("2025-03-04T11:30:00.000000001Z")));
    }

    @ParameterizedTest
    @MethodSource("gradedFields")
    void recordKeyChangesWithEveryGradedField(UnaryOperator<LogRecord> change) {
        assertThat(Identity.recordKey(change.apply(record()))).isNotEqualTo(Identity.recordKey(record()));
    }

    @Test
    void recordKeyIgnoresTheOffsetASiteWritesIn() {
        LogRecord central = record().withObservedAt(NanoTime.parse("2025-03-04T05:30:00-06:00"));
        assertThat(Identity.recordKey(central)).isEqualTo(Identity.recordKey(record()));
    }

    @Test
    void recordKeySeparatesTwoRecordsOneNanosecondApart() {
        LogRecord near = record().withObservedAt(NanoTime.parse("2025-03-04T11:30:00.000000001Z"));
        assertThat(Identity.recordKey(near)).isNotEqualTo(Identity.recordKey(record()));
    }

    @Test
    void recordKeyIgnoresLabelsAndSources() {
        assertThat(Identity.recordKey(record().withLabel("lane", "3"))).isEqualTo(Identity.recordKey(record()));
    }

    @Test
    void recordKeySeparatesFieldsSoAShiftDoesNotCollide() {
        LogRecord left = record().withUnit("pump").withMessage("bridge auth approved");
        LogRecord right = record().withUnit("pump-bridge").withMessage("auth approved");
        assertThat(Identity.recordKey(left)).isNotEqualTo(Identity.recordKey(right));
    }

    @Test
    void recordKeySurvivesAnEncodingRoundTrip() {
        LogRecord original = record().withMessage("prix affýché");
        LogRecord reloaded = record().withMessage(
                        new String(original.message().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThat(Identity.recordKey(original)).isEqualTo(Identity.recordKey(reloaded));
    }

    @Test
    void recordKeyMatchesTheDigestTheCollectorComputes() {
        // Pinned to the key the collector computes, so the site and the collector agree.
        assertThat(Identity.recordKey(record())).isEqualTo(REFERENCE_KEY);
    }

    static final String REFERENCE_KEY = "7cd69a7714583c96bddec27ce22b56c6";
}
