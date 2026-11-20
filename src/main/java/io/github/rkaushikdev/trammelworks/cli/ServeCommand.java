package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.EventLog;
import io.github.rkaushikdev.trammelworks.Level;
import io.github.rkaushikdev.trammelworks.Registry;
import io.github.rkaushikdev.trammelworks.SiteConfig;
import io.github.rkaushikdev.trammelworks.SystemClock;
import io.github.rkaushikdev.trammelworks.service.ServerSettings;
import io.github.rkaushikdev.trammelworks.service.ServiceHost;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Visibility;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code stanchion serve}, the read only face. {@code --host} off loopback is refused unless {@code
 * --any-interface} is also given and a token is set, and the command says which is missing.
 * {@code --once} serves a single request and stops, which is how the install check works.
 */
@Command(name = "serve", description = "Serve the site's status and archive over HTTP, read only.")
final class ServeCommand implements Callable<Integer> {

    @ParentCommand
    Cli root;

    @Option(
            names = "--host",
            defaultValue = ServerSettings.DEFAULT_HOST,
            showDefaultValue = Visibility.ALWAYS,
            description = "Address to bind.")
    String host;

    @Option(
            names = "--port",
            defaultValue = "9787",
            showDefaultValue = Visibility.ALWAYS,
            description = "Port to bind.")
    int port;

    @Option(names = "--token", defaultValue = "", description = "Require this bearer token.")
    String token;

    @Option(names = "--any-interface", description = "Allow binding somewhere the rest of the shop can reach.")
    boolean anyInterface;

    @Option(names = "--once", description = "Serve one request and stop.")
    boolean once;

    @Override
    public Integer call() {
        CliContext context = root.context();
        SiteConfig config = context.reporting(context::load);
        ServerSettings settings = context.reporting(() -> new ServerSettings(host, port, token, anyInterface));
        EventLog events = new EventLog(context.verbose() ? Level.DEBUG : Level.INFO, new SystemClock());
        ServiceHost service = new ServiceHost(config, settings, new SystemClock(), events, new Registry());
        context.reporting(service::build);
        InetSocketAddress address = service.address();
        context.warn("serving " + config.site() + " on http://" + address.getHostString() + ":" + address.getPort());
        if (settings.loopbackOnly()) {
            context.warn("bound to loopback, nothing off this box can reach it");
        } else {
            context.warn("bound off loopback, a token is required for every request");
        }
        try {
            if (once) {
                service.serveOnce();
            } else {
                service.serveForever();
            }
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            context.warn("stopping");
        } finally {
            service.close();
        }
        if (context.verbose()) {
            Map<String, Long> sorted = new TreeMap<>(service.metrics().snapshot());
            sorted.forEach((name, value) -> context.warn(name + " " + value));
        }
        return Cli.EXIT_OK;
    }
}
