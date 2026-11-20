package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ErrorsTest {

    static Stream<Function<String, StanchionError>> subclasses() {
        return Stream.of(
                ConfigError::new,
                IngestError::new,
                ParseError::new,
                QueryError::new,
                RedactionError::new,
                RetentionError::new,
                ShipError::new,
                SpoolError::new);
    }

    @Test
    void baseCarriesMessageAndCode() {
        StanchionError err = new StanchionError("pump bridge went away");
        assertThat(err.message()).isEqualTo("pump bridge went away");
        assertThat(err.code()).isEqualTo("stanchion.error");
        assertThat(err.detail()).isEmpty();
        assertThat(err.getMessage()).isEqualTo("pump bridge went away");
    }

    @Test
    void detailIsAppendedToStr() {
        StanchionError err = new StanchionError("spool is full", "segment 41");
        assertThat(err.getMessage()).isEqualTo("spool is full (segment 41)");
    }

    @Test
    void asDictOmitsAbsentDetail() {
        assertThat(new StanchionError("nope").asDict()).isEqualTo(Map.of("code", "stanchion.error", "message", "nope"));
    }

    @Test
    void asDictIncludesDetail() {
        Map<String, String> payload = new ConfigError("bad site id", "empty string").asDict();
        assertThat(payload)
                .isEqualTo(Map.of("code", "stanchion.config", "message", "bad site id", "detail", "empty string"));
    }

    @ParameterizedTest
    @MethodSource("subclasses")
    void everySubclassDescendsFromTheBase(Function<String, StanchionError> make) {
        StanchionError err = make.apply("x");
        assertThat(err).isInstanceOf(StanchionError.class).isInstanceOf(RuntimeException.class);
        assertThat(err.getClass()).isNotEqualTo(StanchionError.class);
    }

    @ParameterizedTest
    @MethodSource("subclasses")
    void everySubclassHasADistinctCode(Function<String, StanchionError> make) {
        String code = make.apply("x").code();
        assertThat(code).isNotEqualTo(StanchionError.CODE);
        assertThat(code).startsWith("stanchion.");
    }

    @Test
    void codesAreUniqueAcrossTheHierarchy() {
        List<String> codes = subclasses().map(make -> make.apply("x").code()).toList();
        assertThat(new HashSet<>(codes)).hasSameSizeAs(codes);
    }

    @Test
    void parseErrorRecordsSourceAndOffset() {
        ParseError err = new ParseError("not a cri line", null, "/var/log/pods/a.log", 4096L);
        assertThat(err.source()).contains("/var/log/pods/a.log");
        assertThat(err.offset()).contains(4096L);
        assertThat(err.asDict().get("offset")).isEqualTo("4096");
        assertThat(err.asDict().get("source")).isEqualTo("/var/log/pods/a.log");
    }

    @Test
    void parseErrorOffsetZeroIsStillReported() {
        ParseError err = new ParseError("empty file", null, null, 0L);
        assertThat(err.asDict().get("offset")).isEqualTo("0");
    }

    @Test
    void parseErrorWithoutPositionOmitsTheKeys() {
        Map<String, String> payload = new ParseError("bad frame").asDict();
        assertThat(payload).doesNotContainKeys("source", "offset");
    }

    @Test
    void errorsCanBeRaisedAndCaughtAsTheBase() {
        StanchionError caught = catchThrowableOfType(StanchionError.class, () -> {
            throw new SpoolError("out of room", "budget 512 MiB");
        });
        assertThat(caught.code()).isEqualTo("stanchion.spool");
    }

    @Test
    void versionStringIsStable() {
        assertThat(Version.versionString()).isEqualTo("stanchion " + Version.VERSION);
        assertThat(Version.VERSION.chars().filter(c -> c == '.').count()).isEqualTo(2);
    }
}
