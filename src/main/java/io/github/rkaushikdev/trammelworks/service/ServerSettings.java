package io.github.rkaushikdev.trammelworks.service;

import io.github.rkaushikdev.trammelworks.StanchionError;
import java.util.Set;

/**
 * Where the service listens and who may talk to it.
 *
 * <p>It binds to the loopback address unless told otherwise, and telling it otherwise requires
 * saying so and setting a token. A forecourt controller sits on a flat network with the dispenser
 * controllers and the till, and a service bound to every interface by default would be reachable
 * from the whole shop the first time somebody ran it to look at a log.
 *
 * @param host the address to bind
 * @param port the port, zero meaning a free one the kernel picks
 * @param token the bearer token callers must present, or empty
 * @param allowAnyInterface whether binding off loopback was asked for explicitly
 */
public record ServerSettings(String host, int port, String token, boolean allowAnyInterface) {

    /** Where the service listens by default. */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /** The port it listens on by default. */
    public static final int DEFAULT_PORT = 9787;

    /** The names that mean this box only. */
    public static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "localhost");

    /** Checks the port and that leaving loopback was asked for and guarded. */
    public ServerSettings {
        if (port < 0 || port > 65535) {
            throw new StanchionError("port is out of range", Integer.toString(port));
        }
        if (!LOOPBACK.contains(host) && !allowAnyInterface) {
            throw new StanchionError("binding off loopback needs allow_any_interface", host);
        }
        if (!LOOPBACK.contains(host) && token.isEmpty()) {
            throw new StanchionError("a service reachable off the box needs a token");
        }
    }

    /** The defaults: loopback, the usual port, no token. */
    public ServerSettings() {
        this(DEFAULT_HOST, DEFAULT_PORT, "", false);
    }

    /** Loopback on {@code port}, no token. */
    public static ServerSettings onPort(int port) {
        return new ServerSettings(DEFAULT_HOST, port, "", false);
    }

    /** Whether only this box can reach the service. */
    public boolean loopbackOnly() {
        return LOOPBACK.contains(host);
    }
}
