package io.github.rkaushikdev.trammelworks.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.rkaushikdev.trammelworks.Clock;
import io.github.rkaushikdev.trammelworks.EventLog;
import io.github.rkaushikdev.trammelworks.Registry;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.StanchionError;
import io.github.rkaushikdev.trammelworks.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The routes behind a socket.
 *
 * <p>Thin on purpose. Everything that decides anything lives in {@link Service}; this turns an
 * exchange into a {@link ServiceRequest} and a {@link ServiceResponse} back into bytes. It logs
 * through the event log rather than to stderr, because on a box whose stderr goes to the container
 * runtime the service's own chatter would come straight back round as input.
 */
public final class ServiceHost {

    /** Nothing here reads a body, so a large one is drained only up to this. */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private final SiteConfig config;
    private final ServerSettings settings;
    private final Clock clock;
    private final EventLog events;
    private final Registry metrics;
    private HttpServer server;
    private ExecutorService workers;
    private CountDownLatch stopped;
    private CountDownLatch answered;

    /** A host with every collaborator given. */
    public ServiceHost(SiteConfig config, ServerSettings settings, Clock clock, EventLog events, Registry metrics) {
        this.config = config;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
        this.metrics = metrics;
    }

    /** A host with the system clock, no events and fresh metrics. */
    public ServiceHost(SiteConfig config, ServerSettings settings) {
        this(config, settings, new SystemClock(), EventLog.nullLog(), new Registry());
    }

    /** The counters this host keeps. */
    public Registry metrics() {
        return metrics;
    }

    /** The routes as of now. */
    public Service service() {
        return new Service(config, clock.now(), settings.token());
    }

    /** Bind the socket without serving, so a caller can learn the port first. */
    public synchronized HttpServer build() {
        if (server != null) {
            return server;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(settings.host(), settings.port()), 0);
        } catch (IOException exc) {
            throw new StanchionError("the service could not bind", settings.host() + ":" + settings.port(), exc);
        }
        workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext("/", this::exchange);
        stopped = new CountDownLatch(1);
        answered = new CountDownLatch(1);
        return server;
    }

    /** Start answering in the background. */
    public void start() {
        build().start();
        events.info(
                "service_started",
                "host",
                address().getHostString(),
                "port",
                address().getPort());
    }

    /** Start answering and block until {@link #close()} is called from elsewhere. */
    public void serveForever() throws InterruptedException {
        start();
        CountDownLatch waiting = stopped;
        try {
            waiting.await();
        } finally {
            close();
        }
    }

    /**
     * Answer exactly one request and stop. That is how the install check works: start it, ask it
     * for {@code /health}, and know the service can run on this box before a unit file tries it.
     */
    public void serveOnce() throws InterruptedException {
        start();
        CountDownLatch waiting = answered;
        try {
            waiting.await();
        } finally {
            close();
        }
    }

    private void exchange(HttpExchange exchange) throws IOException {
        try (exchange) {
            drain(exchange.getRequestBody());
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders()
                    .forEach((name, values) -> headers.put(name, values.isEmpty() ? "" : values.get(0)));
            String method = exchange.getRequestMethod();
            ServiceRequest request =
                    ServiceRequest.of(exchange.getRequestURI().getRawPath() + query(exchange), headers, method);
            ServiceResponse answer = dispatch(request);
            byte[] body = answer.body();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Server", "stanchion");
            boolean head = method.equalsIgnoreCase("HEAD");
            if (head) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            }
            exchange.sendResponseHeaders(answer.status(), head ? -1 : body.length);
            if (!head) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            events.debug("http", "line", method + " " + request.path() + " " + answer.status());
        } finally {
            CountDownLatch done = answered;
            if (done != null) {
                done.countDown();
            }
        }
    }

    private static String query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        return raw == null ? "" : "?" + raw;
    }

    /**
     * Read and discard a request body before answering: a body left unread means the client is
     * still writing when the socket closes, and it sees a reset instead of the refusal we sent.
     */
    private static void drain(InputStream body) throws IOException {
        body.readNBytes(MAX_BODY_BYTES);
    }

    /** One request, counted and logged. */
    public ServiceResponse dispatch(ServiceRequest request) {
        ServiceResponse answer = service().handle(request);
        metrics.incr("service_requests");
        if (!answer.ok()) {
            metrics.incr("service_refusals");
        }
        events.info("service_request", "path", request.path(), "method", request.method(), "status", answer.status());
        return answer;
    }

    /** Where the service is listening, or was asked to listen before it bound. */
    public synchronized InetSocketAddress address() {
        if (server == null) {
            return InetSocketAddress.createUnresolved(settings.host(), settings.port());
        }
        return server.getAddress();
    }

    /** Stop answering and release the socket. Closing twice is harmless. */
    public synchronized void close() {
        if (server == null) {
            return;
        }
        server.stop(0);
        workers.shutdownNow();
        stopped.countDown();
        server = null;
        workers = null;
        events.info("service_stopped");
    }
}
