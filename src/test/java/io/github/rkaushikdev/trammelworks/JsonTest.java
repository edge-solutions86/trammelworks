package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The encoder, repr and hash helpers, pinned against output from the reference toolchain. */
class JsonTest {

    static Map<String, Object> sample() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("x", "é\n\"\u2028");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("b", Arrays.asList(1, 2.5, null, true));
        out.put("a", inner);
        return out;
    }

    @Test
    void theDefaultFormKeepsOrderAndEscapesToAscii() {
        assertThat(Json.dumps(sample()))
                .isEqualTo("{\"b\": [1, 2.5, null, true], \"a\": {\"x\": \"\\u00e9\\n\\\"\\u2028\"}}");
    }

    @Test
    void anIndentPutsEveryItemOnItsOwnLine() {
        Map<String, Object> value = sample();
        cast(value.get("a")).put("x", "é");
        assertThat(Json.dumps(value, Json.Style.indented(1)))
                .isEqualTo("{\n \"a\": {\n  \"x\": \"\\u00e9\"\n },\n \"b\": [\n  1,\n  2.5,\n  null,\n  true\n ]\n}");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void emptyContainersStayOnOneLine() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", List.of());
        value.put("a", Map.of());
        assertThat(Json.dumps(value, Json.Style.indented(2))).isEqualTo("{\n  \"a\": {},\n  \"b\": []\n}");
    }

    @Test
    void theCompactFormIsSortedWithNoSpaces() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("z", 1);
        value.put("a", List.of(1, Map.of("k", "v")));
        assertThat(Json.dumps(value, Json.Style.compact())).isEqualTo("{\"a\":[1,{\"k\":\"v\"}],\"z\":1}");
    }

    @ParameterizedTest
    @CsvSource({
        "0.1, 0.1",
        "1e16, 1e+16",
        "1e15, 1000000000000000.0",
        "0.0001, 0.0001",
        "0.00001, 1e-05",
        "123456789.123, 123456789.123",
        "-0.0, -0.0",
        "1.5e-7, 1.5e-07",
        "1180591620717411303424.0, 1.1805916207174113e+21",
        "3.0, 3.0"
    })
    void floatsUseTheShortestRoundTrippingForm(double value, String expected) {
        assertThat(Repr.of(value)).isEqualTo(expected);
    }

    @Test
    void stringsAreQuotedTheWayErrorsNameThem() {
        assertThat(Repr.of("it's")).isEqualTo("\"it's\"");
        assertThat(Repr.of("say \"hi\"")).isEqualTo("'say \"hi\"'");
        assertThat(Repr.of("both ' \"")).isEqualTo("'both \\' \"'");
        assertThat(Repr.of("tab\t\u0001é\u200b")).isEqualTo("'tab\\t\\x01é\\u200b'");
    }

    @Test
    void fixedPointRoundsTheExactValueHalfToEven() {
        assertThat(Repr.fixed(0.25, 1)).isEqualTo("0.2");
        assertThat(Repr.fixed(-0.04, 1)).isEqualTo("-0.0");
        assertThat(Repr.fixed(0.1234565, 6)).isEqualTo("0.123456");
        assertThat(Repr.fixed(2.5, 0)).isEqualTo("2");
        assertThat(Repr.signedFixed(1.25, 1)).isEqualTo("+1.2");
    }

    @Test
    void decodingKeepsIntegersIntegral() {
        Object found = Json.loads("{\"a\": 1, \"b\": 1.0, \"c\": 123456789012345678901234567890, \"d\": [true, null]}");
        assertThat(found)
                .isEqualTo(Map.of(
                        "a",
                        1L,
                        "b",
                        1.0,
                        "c",
                        new BigInteger("123456789012345678901234567890"),
                        "d",
                        Arrays.asList(true, null)));
    }

    @Test
    void decodingReadsEscapesAndTheLastDuplicate() {
        assertThat(Json.loads("{\"k\": \"\\u00e9\\n\\/\", \"k\": \"second\"}")).isEqualTo(Map.of("k", "second"));
        assertThat(Json.loads("\"\\u00e9\\n\\/\"")).isEqualTo("é\n/");
    }

    @Test
    void decodingRefusesWhatIsNotJson() {
        for (String bad : List.of("{broken", "[1,]", "1 2", "\"unterminated", "\"a\u0001\"", "", "{\"a\" 1}")) {
            assertThatThrownBy(() -> Json.loads(bad)).isInstanceOf(Json.JsonException.class);
        }
    }

    @Test
    void encodingRefusesWhatIsNotJsonUnlessThereIsAFallback() {
        assertThatThrownBy(() -> Json.dumps(Map.of("a", new Object()))).isInstanceOf(Json.JsonException.class);
        assertThat(Json.dumps(Map.of("a", new NanoTime(0)), Json.Style.plain().withFallback(String::valueOf)))
                .isEqualTo("{\"a\": \"1970-01-01T00:00:00.000000000Z\"}");
    }

    @Test
    void blake2bMatchesTheReferenceDigests() {
        assertThat(new Blake2b(16).hexdigest()).isEqualTo("cae66941d9efbd404e4d88758ea67670");
        assertThat(new Blake2b(16).update("abc").hexdigest()).isEqualTo("cf4ab791c62b8d2b2109c90275287816");
        byte[] ones = new byte[300];
        Arrays.fill(ones, (byte) 0xff);
        assertThat(new Blake2b(32).update(ones).hexdigest())
                .isEqualTo("633163b1240d23f2bfd54ac734f3be0e602227888ddcaeaa23d5e5af68840ff0");
    }
}
