package io.github.rkaushikdev.trammelworks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.EventLog;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Level;
import io.github.rkaushikdev.trammelworks.ManualClock;
import io.github.rkaushikdev.trammelworks.PosixPath;
import io.github.rkaushikdev.trammelworks.Registry;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.StanchionError;
import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceServerTest {

    @TempDir
    Path tmp;

    SiteConfig config;
    ServiceHost running;

    @BeforeEach
    void setUp() {
        config = SiteConfig.builder("US-TX-0417")
                .spool(SpoolSettings.at(PosixPath.asPosix(tmp.resolve("spool"))))
                .archive(ArchiveSettings.at(
                        PosixPath.asPosix(tmp.resolve("archive").resolve("records.sqlite3"))))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (running != null) {
            running.close();
        }
    }

    ServiceHost host(String token) {
        ManualClock clock = new ManualClock();
        clock.setWall("2025-03-04T12:00:00Z");
        return new ServiceHost(
                config, new ServerSettings("127.0.0.1", 0, token, false), clock, EventLog.nullLog(), new Registry());
    }

    ServiceHost started(String token) {
        running = host(token);
        running.start();
        return running;
    }

    record Answer(int status, Map<String, Object> payload) {}

    static Answer fetch(ServiceHost built, String path, String token, String method, String body)
            throws IOException, InterruptedException {
        InetSocketAddress address = built.address();
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://" + address.getHostString() + ":" + address.getPort() + path))
                .timeout(Duration.ofSeconds(5))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (!token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> answer = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(answer.statusCode(), Json.loadsObject(answer.body()));
        }
    }

    static Answer fetch(ServiceHost built, String path, String token) throws IOException, InterruptedException {
        return fetch(built, path, token, "GET", null);
    }

    @Test
    void theDefaultSettingsAreLoopback() {
        assertThat(new ServerSettings().loopbackOnly()).isTrue();
    }

    @Test
    void aPortOutOfRangeIsRefused() {
        assertThatThrownBy(() -> ServerSettings.onPort(70000))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("port is out of range");
    }

    @Test
    void aNegativePortIsRefused() {
        assertThatThrownBy(() -> ServerSettings.onPort(-1))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("port is out of range");
    }

    @Test
    void portZeroMeansAskTheKernel() {
        assertThat(ServerSettings.onPort(0).port()).isZero();
    }

    @Test
    void bindingOffLoopbackNeedsPermission() {
        assertThatThrownBy(() -> new ServerSettings("0.0.0.0", ServerSettings.DEFAULT_PORT, "x", false))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("allow_any_interface");
    }

    @Test
    void bindingOffLoopbackAlsoNeedsAToken() {
        assertThatThrownBy(() -> new ServerSettings("0.0.0.0", ServerSettings.DEFAULT_PORT, "", true))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("needs a token");
    }

    @Test
    void bindingOffLoopbackWithBothIsAllowed() {
        assertThat(new ServerSettings("0.0.0.0", ServerSettings.DEFAULT_PORT, "secret", true).loopbackOnly())
                .isFalse();
    }

    @Test
    void dispatchAnswersHealth() {
        ServiceResponse found = host("").dispatch(ServiceRequest.of("/health"));
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.payload()).containsEntry("site", "US-TX-0417");
    }

    @Test
    void dispatchCountsRequests() {
        ServiceHost built = host("");
        built.dispatch(ServiceRequest.of("/health"));
        built.dispatch(ServiceRequest.of("/nope"));
        assertThat(built.metrics().snapshot())
                .containsEntry("service_requests", 2L)
                .containsEntry("service_refusals", 1L);
    }

    @Test
    void dispatchLogsTheStatus() {
        ManualClock clock = new ManualClock();
        EventLog events = new EventLog(Level.DEBUG, clock);
        ServiceHost built = new ServiceHost(config, ServerSettings.onPort(0), clock, events, new Registry());
        built.dispatch(ServiceRequest.of("/health"));
        Map<String, Object> record = events.records().get(0);
        assertThat(record).containsEntry("event", "service_request").containsEntry("status", 200L);
    }

    @Test
    void theTokenReachesTheService() {
        assertThat(host("secret").dispatch(ServiceRequest.of("/health")).status())
                .isEqualTo(401);
    }

    @Test
    void theAddressBeforeBindingIsWhatWasAskedFor() {
        InetSocketAddress address = new ServiceHost(config, ServerSettings.onPort(9999)).address();
        assertThat(address.getHostString()).isEqualTo("127.0.0.1");
        assertThat(address.getPort()).isEqualTo(9999);
    }

    @Test
    void buildingBindsARealPort() {
        running = host("");
        running.build();
        assertThat(running.address().getPort()).isPositive();
    }

    @Test
    void closingTwiceIsHarmless() {
        ServiceHost built = host("");
        built.build();
        built.close();
        assertThatCode(built::close).doesNotThrowAnyException();
    }

    @Test
    void aRealRequestGetsARealAnswer() throws IOException, InterruptedException {
        Answer answer = fetch(started(""), "/health", "");
        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.payload()).containsEntry("site", "US-TX-0417");
    }

    @Test
    void aRealRequestForAnUnknownRouteIsA404() throws IOException, InterruptedException {
        Answer answer = fetch(started(""), "/admin", "");
        assertThat(answer.status()).isEqualTo(404);
        assertThat(answer.payload()).containsKey("error");
    }

    @Test
    void aRealRequestWithoutATokenIsRefused() throws IOException, InterruptedException {
        ServiceHost built = started("secret");
        assertThat(fetch(built, "/health", "").status()).isEqualTo(401);
        assertThat(fetch(built, "/health", "secret").status()).isEqualTo(200);
    }

    @Test
    void aPostIsRefusedOverTheWire() throws IOException, InterruptedException {
        assertThat(fetch(started(""), "/health", "", "POST", "{}").status()).isEqualTo(405);
    }

    @Test
    void aBodyIsDrainedSoTheClientSeesTheRefusal() throws IOException, InterruptedException {
        assertThat(fetch(started(""), "/health", "", "POST", "x".repeat(4096)).status())
                .isEqualTo(405);
    }
}
