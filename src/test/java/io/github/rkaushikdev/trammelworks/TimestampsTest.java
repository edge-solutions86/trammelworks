package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TimestampsTest {

    static final String EPOCH = "1970-01-01T00:00:00Z";

    @Test
    void epochIsZero() {
        assertThat(NanoTime.parse(EPOCH).epochNs()).isZero();
    }

    @Test
    void parseKeepsAllNineFractionalDigits() {
        NanoTime value = NanoTime.parse("2025-03-04T11:30:00.123456789Z");
        assertThat(value.nanosInSecond()).isEqualTo(123456789);
    }

    @Test
    void parsePadsAShortFractionOnTheRight() {
        assertThat(NanoTime.parse("2025-03-04T11:30:00.5Z").nanosInSecond()).isEqualTo(500000000);
        assertThat(NanoTime.parse("2025-03-04T11:30:00.000001Z").nanosInSecond())
                .isEqualTo(1000);
    }

    @Test
    void parseAcceptsNoFraction() {
        assertThat(NanoTime.parse("2025-03-04T11:30:00Z").nanosInSecond()).isZero();
    }

    @Test
    void parseAcceptsALowercaseMarkerAndASpaceSeparator() {
        NanoTime left = NanoTime.parse("2025-03-04t11:30:00z");
        NanoTime right = NanoTime.parse("2025-03-04 11:30:00Z");
        assertThat(left).isEqualTo(right).isEqualTo(NanoTime.parse("2025-03-04T11:30:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-06:00", "-0600"})
    void parseAppliesANegativeOffset(String zone) {
        assertThat(NanoTime.parse("2025-03-04T05:30:00" + zone)).isEqualTo(NanoTime.parse("2025-03-04T11:30:00Z"));
    }

    @Test
    void parseAppliesAPositiveOffset() {
        assertThat(NanoTime.parse("2025-03-04T13:30:00+02:00")).isEqualTo(NanoTime.parse("2025-03-04T11:30:00Z"));
    }

    @Test
    void parseKeepsTheFractionWhenAnOffsetIsApplied() {
        NanoTime value = NanoTime.parse("2025-03-04T05:30:00.000000042-06:00");
        assertThat(value.nanosInSecond()).isEqualTo(42);
        assertThat(value.toDateTime().getHour()).isEqualTo(11);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "2025-03-04",
                "2025-03-04T11:30:00",
                "2025-03-04T11:30:00.1234567890Z",
                "not a time",
                "2025-03-04T11:30:00+2:00"
            })
    void parseRejectsMalformedInput(String raw) {
        assertThatThrownBy(() -> NanoTime.parse(raw))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("malformed rfc3339");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-13-04T11:30:00Z", "2025-02-30T11:30:00Z"})
    void parseRejectsAnImpossibleDate(String raw) {
        assertThatThrownBy(() -> NanoTime.parse(raw))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("impossible rfc3339");
    }

    @Test
    void parseTrimsSurroundingWhitespace() {
        assertThat(NanoTime.parse("  2025-03-04T11:30:00Z\n")).isEqualTo(NanoTime.parse("2025-03-04T11:30:00Z"));
    }

    @Test
    void fromDatetimeKeepsTheMicrosecond() {
        OffsetDateTime source = OffsetDateTime.of(2025, 3, 4, 11, 30, 0, 123456000, ZoneOffset.UTC);
        assertThat(NanoTime.fromDateTime(source).nanosInSecond()).isEqualTo(123456000);
    }

    @Test
    void fromDatetimeHonoursTheOffset() {
        OffsetDateTime central = OffsetDateTime.of(2025, 3, 4, 5, 30, 0, 0, ZoneOffset.ofHours(-6));
        assertThat(NanoTime.fromDateTime(central)).isEqualTo(NanoTime.parse("2025-03-04T11:30:00Z"));
    }

    @Test
    void fromDatetimeRefusesANaiveValue() {
        assertThatThrownBy(() -> NanoTime.fromDateTime(LocalDateTime.of(2025, 3, 4, 11, 30)))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("naive datetime");
    }

    @Test
    void fromMicrosMatchesTheJournaldConvention() {
        assertThat(NanoTime.fromMicros(1_741_087_800_000_000L)).isEqualTo(NanoTime.parse("2025-03-04T11:30:00Z"));
    }

    @Test
    void secondsAndNanosSplitTheInstant() {
        NanoTime value = NanoTime.parse("2025-03-04T11:30:00.123456789Z");
        assertThat(value.seconds() * NanoTime.NS_PER_SECOND + value.nanosInSecond())
                .isEqualTo(value.epochNs());
    }

    @Test
    void toDatetimeTruncatesRatherThanRounds() {
        NanoTime value = NanoTime.parse("2025-03-04T11:30:00.123456999Z");
        assertThat(value.toDateTime().getNano() / 1000).isEqualTo(123456);
    }

    @Test
    void toDatetimeIsUtcAware() {
        assertThat(NanoTime.parse("2025-03-04T11:30:00Z").toDateTime().getOffset())
                .isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void isoformatAlwaysShowsNineDigits() {
        assertThat(NanoTime.parse("2025-03-04T11:30:00Z").isoformat()).isEqualTo("2025-03-04T11:30:00.000000000Z");
        assertThat(new NanoTime(42).isoformat()).isEqualTo("1970-01-01T00:00:00.000000042Z");
    }

    @Test
    void strIsTheIsoformat() {
        NanoTime value = NanoTime.parse("2025-03-04T11:30:00.5Z");
        assertThat(value.toString()).isEqualTo(value.isoformat());
    }

    @Test
    void orderingSurvivesBelowTheMicrosecond() {
        NanoTime early = NanoTime.parse("2025-03-04T11:30:00.123456001Z");
        NanoTime late = NanoTime.parse("2025-03-04T11:30:00.123456002Z");
        assertThat(early).isLessThan(late);
        List<NanoTime> sorted = new ArrayList<>(List.of(late, early));
        sorted.sort(null);
        assertThat(sorted).containsExactly(early, late);
        assertThat(early.toDateTime()).isEqualTo(late.toDateTime());
    }

    @Test
    void subtractionGivesNanoseconds() {
        NanoTime early = NanoTime.parse("2025-03-04T11:30:00Z");
        NanoTime late = NanoTime.parse("2025-03-04T11:30:01.5Z");
        assertThat(late.minus(early)).isEqualTo(1_500_000_000L);
        assertThat(early.minus(late)).isEqualTo(-1_500_000_000L);
    }

    @Test
    void shiftedMovesByASignedNanosecondDelta() {
        NanoTime base = NanoTime.parse("2025-03-04T11:30:00Z");
        assertThat(base.shifted(1).nanosInSecond()).isEqualTo(1);
        assertThat(base.shifted(-1)).isLessThan(base);
    }

    @Test
    void nanotimeIsHashable() {
        Set<NanoTime> set = new HashSet<>(List.of(new NanoTime(7), new NanoTime(7), new NanoTime(8)));
        assertThat(set).hasSize(2);
    }

    static Stream<Long> epochs() {
        return Samples.longs(0, 4_000_000_000L * NanoTime.NS_PER_SECOND);
    }

    @ParameterizedTest
    @MethodSource("epochs")
    void isoformatRoundTrips(long epochNs) {
        NanoTime value = new NanoTime(epochNs);
        assertThat(NanoTime.parse(value.isoformat())).isEqualTo(value);
    }
}
