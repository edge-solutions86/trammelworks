package io.github.rkaushikdev.trammelworks.clockfix;

import io.github.rkaushikdev.trammelworks.ConfigError;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * The boot id for this run, and where it came from.
 *
 * <p>The kernel exposes a fresh random id per boot at {@code /proc/sys/kernel/random/boot_id}.
 * Where that is missing (a development machine, a hardened image without {@code /proc/sys}) a
 * random id is minted and written beside the spool. It is deliberately not derived from the
 * hostname or machine id: those survive a reboot, and an identifier that survives a reboot would
 * let monotonic readings from two boots be compared, which is the mistake boot ids prevent.
 *
 * @param bootId the boot id
 * @param source {@code kernel}, {@code fallback} or {@code fallback-reused}
 */
public record BootIdentity(String bootId, String source) {

    /** Where the kernel publishes the boot id. */
    public static final String PROC_BOOT_ID = "/proc/sys/kernel/random/boot_id";

    /** The minted id's file, beside the spool. */
    public static final String FALLBACK_FILENAME = "boot_id";

    /** Whether the kernel supplied the id. */
    public boolean fromKernel() {
        return source.equals("kernel");
    }

    private static Optional<String> parsed(Path path) {
        String raw;
        try {
            raw = Files.readString(path, StandardCharsets.US_ASCII);
        } catch (IOException exc) {
            return Optional.empty();
        }
        try {
            return Optional.of(BootSession.parseBootId(raw));
        } catch (ConfigError exc) {
            return Optional.empty();
        }
    }

    /** The kernel's boot id, where there is one. */
    public static Optional<String> readKernelBootId(Path path) {
        return parsed(path);
    }

    /** The id this process wrote for itself last time, if it is still valid. */
    public static Optional<String> readFallbackBootId(Path directory) {
        return parsed(directory.resolve(FALLBACK_FILENAME));
    }

    /** Mint and store a new id for this run. */
    public static String writeFallbackBootId(Path directory) {
        String minted = UUID.randomUUID().toString().replace("-", "");
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(FALLBACK_FILENAME), minted, StandardCharsets.US_ASCII);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        return minted;
    }

    /** The boot id for this run, read from the kernel where it can be. */
    public static BootIdentity current(Path stateDirectory) {
        return current(stateDirectory, Path.of(PROC_BOOT_ID), false);
    }

    /**
     * The boot id for this run. {@code reuseFallback} exists for the replay tool, which runs
     * several passes over one recorded corpus that must agree on the session. A live site never
     * sets it: a new process there means the box rebooted.
     */
    public static BootIdentity current(Path stateDirectory, Path procPath, boolean reuseFallback) {
        Optional<String> fromKernel = readKernelBootId(procPath);
        if (fromKernel.isPresent()) {
            return new BootIdentity(fromKernel.get(), "kernel");
        }
        if (reuseFallback) {
            Optional<String> stored = readFallbackBootId(stateDirectory);
            if (stored.isPresent()) {
                return new BootIdentity(stored.get(), "fallback-reused");
            }
        }
        return new BootIdentity(writeFallbackBootId(stateDirectory), "fallback");
    }
}
