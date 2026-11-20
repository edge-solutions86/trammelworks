package io.github.rkaushikdev.trammelworks.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.ShipError;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShipTransportTest {

    static final String SITE = "US-TX-0417";
    static final String ENDPOINT = "https://c.invalid/v1";

    @TempDir
    Path tmp;

    static Batch batch() {
        return batch(1);
    }

    static Batch batch(int count) {
        return new Batch(
                SITE,
                IntStream.range(0, count).mapToObj(index -> Maps.of("n", index)).toList());
    }

    static Map<String, Object> read(Path path) throws IOException {
        return Json.loadsObject(Files.readString(path, StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202, 204})
    void aTwoHundredIsOk(int status) {
        assertThat(Transport.outcomeFor(status)).isSameAs(Outcome.OK);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 425, 500, 502, 503, 504, 599})
    void aTransientStatusIsRetryable(int status) {
        assertThat(Transport.outcomeFor(status)).isSameAs(Outcome.RETRYABLE);
    }

    @Test
    void aFourTwoNineIsThrottled() {
        assertThat(Transport.outcomeFor(429)).isSameAs(Outcome.THROTTLED);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 413, 422})
    void aClientErrorIsTerminal(int status) {
        assertThat(Transport.outcomeFor(status)).isSameAs(Outcome.TERMINAL);
    }

    @Test
    void aRetryAfterInSecondsIsRead() {
        assertThat(Transport.parseRetryAfter("30")).hasValue(30.0);
        assertThat(Transport.parseRetryAfter(" 2.5 ")).hasValue(2.5);
    }

    @Test
    void anAbsentOrUnreadableRetryAfterIsNone() {
        assertThat(Transport.parseRetryAfter(null)).isEmpty();
        assertThat(Transport.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isEmpty();
        assertThat(Transport.parseRetryAfter("-5")).isEmpty();
    }

    @Test
    void theMemoryTransportKeepsWhatItWasGiven() {
        MemoryTransport transport = new MemoryTransport();
        transport.send(batch(2));
        assertThat(transport.received()).hasSize(1);
        assertThat(transport.received().get(0).count()).isEqualTo(2);
    }

    @Test
    void theMemoryTransportDefaultsToAccepting() {
        assertThat(new MemoryTransport().send(batch()).ok()).isTrue();
    }

    @Test
    void theMemoryTransportFollowsItsScript() {
        MemoryTransport transport =
                new MemoryTransport(List.of(new Response(Outcome.RETRYABLE, 503), new Response(Outcome.OK, 200)));
        assertThat(transport.send(batch()).outcome()).isSameAs(Outcome.RETRYABLE);
        assertThat(transport.send(batch()).outcome()).isSameAs(Outcome.OK);
    }

    @Test
    void theMemoryTransportFallsBackToItsDefaultWhenTheScriptRunsOut() {
        MemoryTransport transport = new MemoryTransport(List.of(new Response(Outcome.RETRYABLE, 503)));
        transport.send(batch());
        assertThat(transport.send(batch()).ok()).isTrue();
    }

    @Test
    void theMemoryTransportReportsTheSizeItSaw() {
        Batch sent = batch(3);
        assertThat(new MemoryTransport().send(sent).bytesSent()).isEqualTo(sent.wireBytes());
    }

    @Test
    void theMemoryTransportListsTheBatchIds() {
        MemoryTransport transport = new MemoryTransport();
        Batch first = batch(1);
        transport.send(first);
        assertThat(transport.batchIds()).containsExactly(first.batchId());
    }

    @Test
    void theNullTransportAcceptsAndCounts() {
        NullTransport transport = new NullTransport();
        transport.send(batch());
        transport.send(batch(2));
        assertThat(transport.sent()).isEqualTo(2);
        assertThat(transport.bytesSent()).isPositive();
    }

    @Test
    void theFileTransportWritesAFilePerBatch() {
        Batch sent = batch(2);
        assertThat(new FileTransport(tmp.resolve("outbox")).send(sent).ok()).isTrue();
        assertThat(tmp.resolve("outbox").resolve(sent.batchId() + ".json")).exists();
    }

    @Test
    void theWrittenFileIsTheBatchPayload() throws IOException {
        Batch sent = batch(2);
        new FileTransport(tmp.resolve("outbox")).send(sent);
        Map<String, Object> payload = read(tmp.resolve("outbox").resolve(sent.batchId() + ".json"));
        assertThat(payload.get("count")).isEqualTo(2L);
        assertThat(payload.get("site")).isEqualTo(SITE);
    }

    @Test
    void theFileTransportCreatesItsDirectory() {
        new FileTransport(tmp.resolve("deep").resolve("outbox")).send(batch());
        assertThat(tmp.resolve("deep").resolve("outbox")).isDirectory();
    }

    @Test
    void writingTheSameBatchTwiceIsOneFile() throws IOException {
        FileTransport transport = new FileTransport(tmp.resolve("outbox"));
        Batch sent = batch(2);
        transport.send(sent);
        transport.send(sent);
        try (Stream<Path> found = Files.list(tmp.resolve("outbox"))) {
            assertThat(found.count()).isEqualTo(1);
        }
    }

    @Test
    void aFileTransportThatCannotWriteIsRetryable() throws IOException {
        Path blocker = Files.writeString(tmp.resolve("outbox"), "not a directory");
        assertThat(new FileTransport(blocker).send(batch()).outcome()).isSameAs(Outcome.RETRYABLE);
    }

    @Test
    void anHttpTransportNeedsAnHttpUrl() {
        assertThatThrownBy(() -> new HttpTransport("collector.invalid"))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("must be an http url");
    }

    @Test
    void anHttpTransportNeedsAPositiveTimeout() {
        assertThatThrownBy(() -> new HttpTransport(ENDPOINT, 0, "", null, null))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("timeout must be positive");
    }

    @Test
    void aSuccessfulPostIsOk() {
        Map<String, Object> seen = new HashMap<>();
        HttpTransport transport = HttpTransport.with(ENDPOINT, (request, timeout) -> {
            seen.put("url", request.url());
            seen.put("body", request.body());
            seen.put("timeout", timeout);
            return new HttpTransport.Answer(200);
        });
        assertThat(transport.send(batch(2)).ok()).isTrue();
        assertThat(seen.get("url")).isEqualTo(ENDPOINT);
        assertThat(Json.loadsObject(new String((byte[]) seen.get("body"), StandardCharsets.UTF_8))
                        .get("count"))
                .isEqualTo(2L);
    }

    @Test
    void theTokenBecomesABearerHeader() {
        Map<String, Object> seen = new HashMap<>();
        new HttpTransport(ENDPOINT, HttpTransport.DEFAULT_TIMEOUT, "abc", null, (request, timeout) -> {
                    seen.put("auth", request.header("Authorization").orElse(null));
                    return new HttpTransport.Answer(200);
                })
                .send(batch());
        assertThat(seen.get("auth")).isEqualTo("Bearer abc");
    }

    @Test
    void noTokenMeansNoAuthHeader() {
        Map<String, Object> seen = new HashMap<>();
        HttpTransport.with(ENDPOINT, (request, timeout) -> {
                    seen.put("auth", request.header("Authorization").orElse(null));
                    return new HttpTransport.Answer(200);
                })
                .send(batch());
        assertThat(seen.get("auth")).isNull();
    }

    @Test
    void anHttpErrorIsMappedAndCarriesItsHint() {
        Response result = HttpTransport.with(
                        ENDPOINT,
                        (request, timeout) -> new HttpTransport.Answer(429, Map.of("Retry-After", "17"), "slow down"))
                .send(batch());
        assertThat(result.outcome()).isSameAs(Outcome.THROTTLED);
        assertThat(result.retryAfter()).hasValue(17.0);
        assertThat(result.status()).isEqualTo(429);
    }

    @Test
    void aRefusedConnectionIsRetryable() {
        Response result = HttpTransport.with(ENDPOINT, (request, timeout) -> {
                    throw new ConnectException("connection refused");
                })
                .send(batch());
        assertThat(result.outcome()).isSameAs(Outcome.RETRYABLE);
        assertThat(result.message()).contains("refused");
    }

    @Test
    void aTimeoutIsRetryable() {
        Response result = HttpTransport.with(ENDPOINT, (request, timeout) -> {
                    throw new HttpTimeoutException("timed out");
                })
                .send(batch());
        assertThat(result.outcome()).isSameAs(Outcome.RETRYABLE);
    }

    @Test
    void aBadRequestIsTerminal() {
        Response result = HttpTransport.with(
                        ENDPOINT, (request, timeout) -> new HttpTransport.Answer(400, Map.of(), "no"))
                .send(batch());
        assertThat(result.outcome()).isSameAs(Outcome.TERMINAL);
    }

    @Test
    void theBytesSentAreReportedEvenOnAFailure() {
        Batch sent = batch(3);
        Response result = HttpTransport.with(ENDPOINT, (request, timeout) -> {
                    throw new IOException("gone");
                })
                .send(sent);
        assertThat(result.bytesSent()).isEqualTo(sent.wireBytes());
    }

    @Test
    void decodeBodyReadsAJsonObject() {
        assertThat(Transport.decodeBody("{\"accepted\": 4}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(Map.of("accepted", 4L));
    }

    @Test
    void decodeBodyOfJunkIsEmpty() {
        assertThat(Transport.decodeBody("<html>gateway timeout</html>".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
        assertThat(Transport.decodeBody("[1,2]".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
    }
}
