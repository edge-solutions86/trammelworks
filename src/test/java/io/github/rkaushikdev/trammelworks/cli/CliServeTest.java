package io.github.rkaushikdev.trammelworks.cli;

import static io.github.rkaushikdev.trammelworks.cli.CliFixtures.posix;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.Json;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliServeTest {

    @TempDir
    Path tmp;

    Path config;

    @BeforeEach
    void setUp() {
        config = CliFixtures.writeConfig(
                tmp,
                "site: US-TX-0417\n"
                        + "spool:\n"
                        + "  directory: " + posix(tmp.resolve("spool")) + "\n"
                        + "archive:\n"
                        + "  path: " + posix(tmp.resolve("archive").resolve("records.sqlite3")) + "\n");
    }

    CliRunner.Result invoke(String... args) {
        String[] full = new String[args.length + 1];
        full[0] = "serve";
        System.arraycopy(args, 0, full, 1, args.length);
        return CliFixtures.invoke(config, full);
    }

    static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    /** Serve once in the background, ask for health until it answers, and hand back both sides. */
    record Served(CliRunner.Result result, Map<String, Object> payload) {}

    Served serveOnce(int port) throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<CliRunner.Result> running =
                CompletableFuture.supplyAsync(() -> invoke("--port", Integer.toString(port), "--once"));
        Map<String, Object> payload = null;
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (int attempt = 0; attempt < 100 && payload == null; attempt++) {
                try {
                    HttpResponse<String> answer = client.send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                                    .timeout(Duration.ofSeconds(5))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    payload = Json.loadsObject(answer.body());
                } catch (IOException exc) {
                    Thread.sleep(50);
                }
            }
        }
        return new Served(running.get(10, TimeUnit.SECONDS), payload);
    }

    @Test
    void bindingOffLoopbackIsRefusedWithoutPermission() {
        CliRunner.Result result = invoke("--host", "0.0.0.0", "--token", "x", "--once");
        assertThat(result.exitCode()).isNotEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("allow_any_interface");
    }

    @Test
    void bindingOffLoopbackIsRefusedWithoutAToken() {
        CliRunner.Result result = invoke("--host", "0.0.0.0", "--any-interface", "--once");
        assertThat(result.exitCode()).isNotEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("needs a token");
    }

    @Test
    void aBadPortIsRefused() {
        CliRunner.Result result = invoke("--port", "70000", "--once");
        assertThat(result.exitCode()).isNotEqualTo(Cli.EXIT_OK);
        assertThat(result.output()).contains("port is out of range");
    }

    @Test
    void aMissingConfigIsRefused() {
        assertThat(CliRunner.invoke("--config", tmp.resolve("nope.yaml").toString(), "serve", "--once")
                        .output())
                .contains("stanchion.config");
    }

    @Test
    void oneRequestIsServedAndThenItStops() throws Exception {
        Served served = serveOnce(freePort());
        assertThat(served.payload()).isNotNull().containsEntry("site", "US-TX-0417");
        assertThat(served.result().exitCode()).isEqualTo(Cli.EXIT_OK);
    }

    @Test
    void theStartupLineNamesTheAddress() throws Exception {
        int port = freePort();
        Served served = serveOnce(port);
        assertThat(served.result().output())
                .contains("http://127.0.0.1:" + port)
                .contains("nothing off this box can reach it");
    }
}
