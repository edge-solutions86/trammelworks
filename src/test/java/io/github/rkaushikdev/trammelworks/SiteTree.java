package io.github.rkaushikdev.trammelworks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A temporary directory laid out the way a commissioned box is. */
public final class SiteTree {

    private final Path root;
    private final String namespace;
    private final Map<String, Long> written = new HashMap<>();

    /** A tree under {@code root} in the forecourt namespace. */
    public SiteTree(Path root) {
        this(root, "forecourt");
    }

    /** A tree under {@code root} in a given namespace. */
    public SiteTree(Path root, String namespace) {
        this.root = root;
        this.namespace = namespace;
    }

    /** The log root. */
    public Path root() {
        return root;
    }

    /** How many bytes each written file holds, by path. */
    public Map<String, Long> written() {
        return written;
    }

    /** The CRI path of a unit's first run, its directory created. */
    public Path criPath(String unit) {
        return criPath(unit, 0);
    }

    /** The CRI path of one run of a unit, its directory created. */
    public Path criPath(String unit, int attempt) {
        Path podDir = root.resolve("pods")
                .resolve(namespace + "_" + unit + "-0_" + LogFiles.POD_UID)
                .resolve(unit);
        return mkdirs(podDir).resolve(attempt + ".log");
    }

    /** The flat symlink directory path of a container, its directory created. */
    public Path flatPath(String unit, String containerId) {
        Path flat = mkdirs(root.resolve("containers"));
        return flat.resolve(unit + "-0_" + namespace + "_" + unit + "-" + containerId + ".log");
    }

    /**
     * The json-file path of a container. The container directory sits straight under the root; two
     * sixty four character names in one path is already close to the Windows limit.
     */
    public Path dockerPath(String containerId) {
        return mkdirs(root.resolve(containerId)).resolve(containerId + "-json.log");
    }

    private static Path mkdirs(Path directory) {
        try {
            return Files.createDirectories(directory);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    /** Append text and remember how many bytes the file now holds. */
    public Path write(Path target, String text) {
        try {
            Files.writeString(
                    target, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            written.put(target.toString(), Files.size(target));
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        return target;
    }

    /** Append lines to a unit's first run. */
    public Path writeCri(String unit, List<String> lines) {
        return writeCri(unit, lines, 0);
    }

    /** Append lines to one run of a unit. */
    public Path writeCri(String unit, List<String> lines, int attempt) {
        return write(criPath(unit, attempt), String.join("", lines));
    }

    /** Move a file aside the way logrotate does, leaving a fresh empty one. */
    public Path rotate(Path target) {
        Path rotated = target.resolveSibling(target.getFileName() + ".1");
        try {
            Files.deleteIfExists(rotated);
            Files.move(target, rotated);
            Files.createFile(target);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        return rotated;
    }

    /** Cut a file short, which is what a power cut leaves behind. */
    public void truncate(Path target, int keep) {
        try {
            byte[] data = Files.readAllBytes(target);
            Files.write(target, Arrays.copyOf(data, Math.min(keep, data.length)));
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }
}
