package io.github.rkaushikdev.trammelworks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The systemd units a commissioned site runs.
 *
 * <p>These are generated rather than shipped as files, because every path in them comes out of the
 * site config: the spool, the state beside it, the archive, the log root that gets a read only bind.
 * A static unit would name them again, and a unit naming a path the config has moved is a site that
 * starts, finds nothing, and reports healthy.
 *
 * <p>Three units, because they fail differently: <b>collect</b> runs forever and restarts always;
 * <b>housekeep</b> is a nightly timer, and a failed run is not urgent; <b>serve</b> is the optional
 * read only query service. The hardening lines keep the blast radius of a bug to one directory, and
 * {@code After=} names the container runtime rather than the network, because the uplink being down
 * is a forecourt's normal state while the runtime not being up means an empty log root.
 */
public final class Deploy {

    /** The account the units run as by default. */
    public static final String DEFAULT_USER = "stanchion";

    /** The group the units run as by default. */
    public static final String DEFAULT_GROUP = "stanchion";

    /** Where the command is installed by default. */
    public static final String DEFAULT_EXECUTABLE = "/usr/local/bin/stanchion";

    /** Where the site config lives by default. */
    public static final String DEFAULT_CONFIG = "/etc/stanchion/site.yaml";

    /** Where units are installed. */
    public static final String UNIT_DIRECTORY = "/etc/systemd/system";

    /** What the container runtime is called on the two images in the estate. */
    public static final List<String> RUNTIME_UNITS = List.of("containerd.service", "docker.service");

    /** The hardening every service unit carries. */
    public static final List<String> COMMON_HARDENING = List.of(
            "NoNewPrivileges=yes",
            "PrivateTmp=yes",
            "PrivateDevices=yes",
            "ProtectSystem=strict",
            "ProtectHome=yes",
            "ProtectKernelTunables=yes",
            "ProtectControlGroups=yes",
            "RestrictSUIDSGID=yes",
            "RestrictRealtime=yes",
            "LockPersonality=yes",
            "MemoryDenyWriteExecute=yes",
            "SystemCallArchitectures=native");

    private Deploy() {}

    /**
     * A path as a unit file has to spell it: always POSIX, because these files describe a Linux box
     * however the engineer produced them, and a backslash in {@code ReadWritePaths=} grants access to
     * nothing.
     */
    public static String unitPath(String raw) {
        return PosixPath.of(Path.of(raw)).toString();
    }

    /**
     * Every directory this process has any business writing to, sorted and without repeats.
     * Directories rather than files, because systemd cannot grant write access to a path that is not
     * there yet.
     */
    public static List<String> writablePaths(SiteConfig config) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        found.add(unitPath(config.spool().directory()));
        found.add(unitPath(Site.stateDirectory(config.spool().directory()).toString()));
        if (config.archive().enabled()) {
            found.add(PosixPath.of(unitPath(config.archive().path())).parent().toString());
        }
        List<String> out = new ArrayList<>(found);
        out.sort(Json::compareCodePoints);
        return out;
    }

    private static String lines(List<String> lines) {
        return String.join("\n", lines) + "\n";
    }

    private static String serviceBody(SiteConfig config, Deployment deployment, String description, String command) {
        List<String> out = new ArrayList<>(List.of(
                "[Unit]",
                "Description=" + description,
                "After=" + String.join(" ", RUNTIME_UNITS),
                "Wants=" + String.join(" ", RUNTIME_UNITS),
                "",
                "[Service]",
                "User=" + deployment.user(),
                "Group=" + deployment.group(),
                "ExecStart=" + deployment.executable() + " --config " + unitPath(deployment.configPath()) + " "
                        + command,
                "ReadWritePaths=" + String.join(" ", writablePaths(config)),
                "BindReadOnlyPaths=" + unitPath(config.ingest().logRoot())));
        out.addAll(COMMON_HARDENING);
        return lines(out);
    }

    /** The long running collector. */
    public static SystemdUnit collectUnit(SiteConfig config, Deployment deployment) {
        String body = serviceBody(config, deployment, "stanchion collector for " + config.site(), "run")
                + lines(List.of("Restart=always", "RestartSec=15", "", "[Install]", "WantedBy=multi-user.target"));
        return new SystemdUnit("stanchion-collect.service", body);
    }

    /** The nightly retention, cap and vacuum pass. */
    public static SystemdUnit housekeepUnit(SiteConfig config, Deployment deployment) {
        String body = serviceBody(config, deployment, "stanchion housekeeping for " + config.site(), "housekeep")
                + lines(List.of("Type=oneshot"));
        return new SystemdUnit("stanchion-housekeep.service", body);
    }

    /**
     * When the housekeeping runs. {@code Persistent=yes} matters more than the hour: a site that was
     * off at twenty past three would otherwise skip the pass and wait a day, which on a card that is
     * already full is the day it fills.
     */
    public static SystemdUnit housekeepTimer(Deployment deployment) {
        String body = lines(List.of(
                "[Unit]",
                "Description=stanchion nightly housekeeping",
                "",
                "[Timer]",
                "OnCalendar=*-*-* " + deployment.housekeepAt() + ":00",
                "RandomizedDelaySec=900",
                "Persistent=yes",
                "",
                "[Install]",
                "WantedBy=timers.target"));
        return new SystemdUnit("stanchion-housekeep.timer", body);
    }

    /** The read only query service, for a site an engineer visits. */
    public static SystemdUnit serveUnit(SiteConfig config, Deployment deployment) {
        String body = serviceBody(config, deployment, "stanchion read only query service for " + config.site(), "serve")
                + lines(List.of("Restart=on-failure", "RestartSec=30", "", "[Install]", "WantedBy=multi-user.target"));
        return new SystemdUnit("stanchion-serve.service", body);
    }

    /** Every unit this site should have installed, with the default deployment. */
    public static List<SystemdUnit> unitsFor(SiteConfig config) {
        return unitsFor(config, new Deployment());
    }

    /** Every unit this site should have installed. */
    public static List<SystemdUnit> unitsFor(SiteConfig config, Deployment deployment) {
        List<SystemdUnit> found = new ArrayList<>(List.of(
                collectUnit(config, deployment), housekeepUnit(config, deployment), housekeepTimer(deployment)));
        if (deployment.serve()) {
            found.add(serveUnit(config, deployment));
        }
        return found;
    }

    /** Write the generated units into a directory. Returns what was written. */
    public static List<Path> writeUnits(List<SystemdUnit> units, Path directory) {
        List<Path> written = new ArrayList<>();
        try {
            Files.createDirectories(directory);
            for (SystemdUnit unit : units) {
                Path target = directory.resolve(unit.name());
                Files.writeString(target, unit.body(), StandardCharsets.UTF_8);
                written.add(target);
            }
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        return written;
    }
}
