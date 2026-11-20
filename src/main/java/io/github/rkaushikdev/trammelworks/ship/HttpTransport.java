package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.ShipError;
import io.github.rkaushikdev.trammelworks.Version;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One small JSON POST per batch to the estate collector.
 *
 * <p>A response of any status is mapped through {@link Transport#outcomeFor}; a send with no status
 * at all (refused, reset, timed out) is always retryable, and it cost the bytes either way.
 */
public final class HttpTransport implements Transport {

    /** The default timeout, in seconds. */
    public static final double DEFAULT_TIMEOUT = 30.0;

    /**
     * One request as it goes out.
     *
     * @param url where it goes
     * @param body the batch
     * @param headers every header, in the order they were added
     */
    public record Request(String url, byte[] body, Map<String, String> headers) {

        /** A header by name, ignoring case, when it was sent. */
        public Optional<String> header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst();
        }
    }

    /**
     * What came back, whatever the status.
     *
     * @param status the HTTP status
     * @param headers the response headers
     * @param reason the status line's text, when there is one
     */
    public record Answer(int status, Map<String, String> headers, String reason) {

        /** An answer with only a status. */
        public Answer(int status) {
            this(status, Map.of(), "");
        }

        Optional<String> header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst();
        }
    }

    /** What actually performs the POST. Tests and the replay tool supply their own. */
    @FunctionalInterface
    public interface Opener {
        /** Send the request; throws when the link fails before any status arrives. */
        Answer open(Request request, double timeoutSeconds) throws IOException, InterruptedException;
    }

    private final String endpoint;
    private final double timeout;
    private final String token;
    private final Signer signer;
    private final Opener opener;

    /** A transport with the default timeout, no token and no signing. */
    public HttpTransport(String endpoint) {
        this(endpoint, DEFAULT_TIMEOUT, "", null, null);
    }

    /** A transport with everything given; {@code signer} and {@code opener} may be {@code null}. */
    public HttpTransport(String endpoint, double timeout, String token, Signer signer, Opener opener) {
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new ShipError("endpoint must be an http url", endpoint);
        }
        if (timeout <= 0) {
            throw new ShipError("timeout must be positive", Repr.of(timeout));
        }
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.token = token;
        this.signer = signer;
        this.opener = opener != null ? opener : HttpTransport::post;
    }

    /** A transport using a given opener. */
    public static HttpTransport with(String endpoint, Opener opener) {
        return new HttpTransport(endpoint, DEFAULT_TIMEOUT, "", null, opener);
    }

    Request request(Batch batch, byte[] body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", Version.userAgent());
        headers.put("Content-Length", Integer.toString(body.length));
        if (!token.isEmpty()) {
            headers.put("Authorization", "Bearer " + token);
        }
        if (signer != null) {
            headers.put(SIGNATURE_HEADER, signer.headerFor(batch, body));
        }
        return new Request(endpoint, body, headers);
    }

    @Override
    public Response send(Batch batch) {
        byte[] body = batch.encode();
        Request request = request(batch, body);
        Answer answer;
        try {
            answer = opener.open(request, timeout);
        } catch (IOException exc) {
            // No status at all: the link went away mid request. Always worth another go.
            return new Response(
                    Outcome.RETRYABLE,
                    0,
                    OptionalDouble.empty(),
                    Objects.toString(exc.getMessage(), exc.toString()),
                    body.length);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            return new Response(Outcome.RETRYABLE, 0, OptionalDouble.empty(), "interrupted", body.length);
        }
        Outcome outcome = Transport.outcomeFor(answer.status());
        if (outcome == Outcome.OK || answer.status() < 400) {
            return new Response(outcome, answer.status(), OptionalDouble.empty(), "", body.length);
        }
        return new Response(
                outcome,
                answer.status(),
                Transport.parseRetryAfter(answer.header("Retry-After").orElse(null)),
                answer.reason(),
                body.length);
    }

    private static Answer post(Request request, double timeoutSeconds) throws IOException, InterruptedException {
        Duration limit = Duration.ofMillis((long) (timeoutSeconds * 1000));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(limit)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()))
                .timeout(limit)
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.body()));
        request.headers().forEach((name, value) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.equals("content-length") && !lower.equals("host")) {
                builder.header(name, value);
            }
        });
        HttpResponse<Void> response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> headers.put(name, values.isEmpty() ? "" : values.get(0)));
        return new Answer(response.statusCode(), headers, "");
    }
}
