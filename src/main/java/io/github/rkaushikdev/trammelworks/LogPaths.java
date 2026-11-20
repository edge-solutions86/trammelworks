package io.github.rkaushikdev.trammelworks;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Working out what a container log file is from where it sits.
 *
 * <p>A site controller can present three layouts, and which one depends on how the box was
 * commissioned: CRI under {@code /var/log/pods}, the flat symlink directory the same runtime keeps
 * under {@code /var/log/containers}, and docker json-file on the 2019 estate. The attempt number is
 * the restart count and only the CRI layout carries it, which is why it is kept apart from the unit.
 */
public final class LogPaths {

    static final Pattern CRI_POD_DIR = Pattern.compile("^(?<namespace>[^_]+)_(?<pod>[^_]+)_(?<uid>[0-9a-f-]{8,})$");
    static final Pattern CRI_FILE = Pattern.compile("^(?<attempt>\\d+)\\.log(?<rotation>(?:\\.\\d+)?)$");
    static final Pattern FLAT_FILE =
            Pattern.compile("^(?<pod>[^_]+)_(?<namespace>[^_]+)_(?<container>.+)-(?<cid>[0-9a-f]{64})\\.log$");
    static final Pattern DOCKER_FILE = Pattern.compile("^(?<cid>[0-9a-f]{64})-json\\.log(?<rotation>(?:\\.\\d+)?)$");

    /** Suffixes a compressed rotation carries. */
    public static final java.util.List<String> COMPRESSED_SUFFIXES = java.util.List.of(".gz", ".zst");

    /** Sorts a rotated set oldest first, compressed files before their plain twins. */
    public static final Comparator<LogPathInfo> ROTATION_ORDER = Comparator.comparingInt(
                    (LogPathInfo info) -> -info.rotation())
            .thenComparingInt(info -> info.compressed() ? 0 : 1);

    private LogPaths() {}

    /** Classify a filesystem path, read with forward slashes. */
    public static LogPathInfo parseLogPath(Path path) {
        return parseLogPath(PosixPath.asPosix(path));
    }

    /** Classify a container log path, or say why it cannot be classified. */
    public static LogPathInfo parseLogPath(String raw) {
        PosixPath target = PosixPath.of(raw);
        String fullName = target.name();
        boolean compressed = false;
        String name = fullName;
        for (String suffix : COMPRESSED_SUFFIXES) {
            if (fullName.endsWith(suffix)) {
                name = fullName.substring(0, fullName.length() - suffix.length());
                compressed = true;
                break;
            }
        }
        PosixPath parent = target.parent();
        String shown = target.toString();

        Matcher docker = DOCKER_FILE.matcher(name);
        if (docker.find()) {
            String cid = docker.group("cid");
            if (!parent.name().equals(cid)) {
                throw ParseError.at("docker log is not in its own container directory", shown);
            }
            return new LogPathInfo(
                    shown,
                    Layout.DOCKER,
                    unitFromDocker(cid),
                    Optional.empty(),
                    Optional.of(cid),
                    Optional.empty(),
                    Optional.empty(),
                    rotationIndex(docker.group("rotation"), shown),
                    compressed);
        }

        Matcher flat = FLAT_FILE.matcher(name);
        if (flat.find()) {
            return new LogPathInfo(
                    shown,
                    Layout.FLAT,
                    Identity.parseUnit(flat.group("container")),
                    Optional.empty(),
                    Optional.of(flat.group("cid")),
                    Optional.of(flat.group("namespace")),
                    Optional.of(flat.group("pod")),
                    0,
                    compressed);
        }

        Matcher cri = CRI_FILE.matcher(name);
        if (cri.find()) {
            Matcher podDir = CRI_POD_DIR.matcher(parent.parent().name());
            if (!podDir.find()) {
                throw ParseError.at("cri log has no pod directory", shown);
            }
            return new LogPathInfo(
                    shown,
                    Layout.CRI,
                    Identity.parseUnit(parent.name()),
                    Optional.of(number(cri.group("attempt"), shown)),
                    Optional.empty(),
                    Optional.of(podDir.group("namespace")),
                    Optional.of(podDir.group("pod")),
                    rotationIndex(cri.group("rotation"), shown),
                    compressed);
        }

        throw ParseError.at("unrecognised container log path", shown);
    }

    private static int rotationIndex(String raw, String shown) {
        return raw.isEmpty() ? 0 : number(raw.substring(1), shown);
    }

    private static int number(String digits, String shown) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException exc) {
            throw ParseError.at("unrecognised container log path", shown);
        }
    }

    /**
     * Docker json-file paths carry no name, so the short id stands in until the runtime's own
     * metadata supplies a better one: something stable and greppable rather than an empty string.
     */
    public static String unitFromDocker(String containerId) {
        return "c" + containerId.substring(0, Math.min(12, containerId.length()));
    }
}
