package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class UnitsTest {

    @ParameterizedTest
    @CsvSource({
        "0, 0",
        "512, 512",
        "512B, 512",
        "1KiB, 1024",
        "1kb, 1000",
        "1MiB, 1048576",
        "1MB, 1000000",
        "512MiB, 536870912",
        "2GiB, 2147483648",
        "1TiB, 1099511627776",
        "1TB, 1000000000000"
    })
    void parseBytesReadsBothSuffixFamilies(String raw, long expected) {
        assertThat(Units.parseBytes(raw)).isEqualTo(expected);
    }

    @Test
    void parseBytesTreatsBinaryAndDecimalAsDifferent() {
        assertThat(Units.parseBytes("1MiB")).isNotEqualTo(Units.parseBytes("1MB"));
        assertThat(Units.parseBytes("1MiB") - Units.parseBytes("1MB")).isEqualTo(48576);
    }

    @Test
    void parseBytesIsCaseAndSpaceInsensitive() {
        assertThat(Units.parseBytes("  4 gib ")).isEqualTo(Units.parseBytes("4GiB"));
    }

    @Test
    void parseBytesAcceptsAFractionThatLandsOnAByte() {
        assertThat(Units.parseBytes("1.5GB")).isEqualTo(1500000000L);
        assertThat(Units.parseBytes("0.5KiB")).isEqualTo(512);
    }

    @Test
    void parseBytesRejectsAFractionOfAByte() {
        assertThatThrownBy(() -> Units.parseBytes("1.5B"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("whole number of bytes");
    }

    @Test
    void parseBytesAcceptsAPlainInteger() {
        assertThat(Units.parseBytes(4096L)).isEqualTo(4096);
    }

    @Test
    void parseBytesRejectsANegativeInteger() {
        assertThatThrownBy(() -> Units.parseBytes(-1L))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("negative byte size");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "MiB", "12 quarts", "1.2.3MB", "-4MiB", "0x40"})
    void parseBytesRejectsJunk(String raw) {
        assertThatThrownBy(() -> Units.parseBytes(raw)).isInstanceOf(ConfigError.class);
    }

    @ParameterizedTest
    @CsvSource({
        "0, 0B",
        "1, 1B",
        "1023, 1023B",
        "1024, 1KiB",
        "1536, 1536B",
        "1048576, 1MiB",
        "536870912, 512MiB",
        "1099511627776, 1TiB"
    })
    void formatBytesPicksTheLargestExactSuffix(long count, String expected) {
        assertThat(Units.formatBytes(count)).isEqualTo(expected);
    }

    @Test
    void formatBytesRefusesANegativeCount() {
        assertThatThrownBy(() -> Units.formatBytes(-8))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("negative byte size");
    }

    static Stream<Long> byteCounts() {
        return Samples.longs(0, 1L << 42);
    }

    @ParameterizedTest
    @MethodSource("byteCounts")
    void byteFormattingRoundTrips(long count) {
        assertThat(Units.parseBytes(Units.formatBytes(count))).isEqualTo(count);
    }

    @ParameterizedTest
    @CsvSource({
        "0s, 0",
        "30s, 30",
        "90s, 90",
        "5m, 300",
        "2h, 7200",
        "7d, 604800",
        "1w, 604800",
        "250ms, 0.25",
        "2h30m, 9000",
        "1d12h, 129600",
        "1m30s, 90",
        "45, 45"
    })
    void parseDurationReadsCompoundSpellings(String raw, double seconds) {
        assertThat(Units.parseDuration(raw)).isEqualTo(Units.ofSeconds(seconds));
    }

    @Test
    void parseDurationIgnoresInternalSpaces() {
        assertThat(Units.parseDuration("2h 30m")).isEqualTo(Duration.ofHours(2).plusMinutes(30));
    }

    @Test
    void parseDurationAcceptsABareNumber() {
        assertThat(Units.parseDuration(90)).isEqualTo(Duration.ofSeconds(90));
        assertThat(Units.parseDuration(0.5)).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void parseDurationRejectsANegativeNumber() {
        assertThatThrownBy(() -> Units.parseDuration(-1))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("negative duration");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "later", "10y", "5s3", "s", "1h!"})
    void parseDurationRejectsJunk(String raw) {
        assertThatThrownBy(() -> Units.parseDuration(raw)).isInstanceOf(ConfigError.class);
    }

    @Test
    void parseDurationRejectsALeadingJunkPrefix() {
        assertThatThrownBy(() -> Units.parseDuration("about2h"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("malformed duration");
    }

    static Stream<Object[]> compactSpellings() {
        return Stream.of(
                new Object[] {Duration.ZERO, "0s"},
                new Object[] {Duration.ofSeconds(45), "45s"},
                new Object[] {Duration.ofMinutes(1).plusSeconds(30), "1m30s"},
                new Object[] {Duration.ofHours(2).plusMinutes(30), "2h30m"},
                new Object[] {Duration.ofDays(1).plusHours(12), "1d12h"},
                new Object[] {Duration.ofDays(14), "2w"},
                new Object[] {Duration.ofMillis(250), "250ms"});
    }

    @ParameterizedTest
    @MethodSource("compactSpellings")
    void formatDurationUsesTheCompactSpelling(Duration value, String expected) {
        assertThat(Units.formatDuration(value)).isEqualTo(expected);
    }

    @Test
    void formatDurationRefusesANegativeValue() {
        assertThatThrownBy(() -> Units.formatDuration(Duration.ofSeconds(-1)))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("negative duration");
    }

    static Stream<Long> durations() {
        return Samples.longs(0, 60L * 60 * 24 * 400);
    }

    @ParameterizedTest
    @MethodSource("durations")
    void durationFormattingRoundTrips(long seconds) {
        Duration value = Duration.ofSeconds(seconds);
        assertThat(Units.parseDuration(Units.formatDuration(value))).isEqualTo(value);
    }
}
