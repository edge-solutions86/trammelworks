package io.github.rkaushikdev.trammelworks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** One small state file, with the corrupt count everybody wants. */
public final class JsonStore {

    private final Path path;
    private final Integer version;
    private final Integer indent;
    private int corrupt;

    /** A store with no version stamp and single line output. */
    public JsonStore(Path path) {
        this(path, null, null);
    }

    /** A store stamping and checking {@code version}, which may be {@code null}. */
    public JsonStore(Path path, Integer version) {
        this(path, version, null);
    }

    /** A store with an optional version and an optional indent. */
    public JsonStore(Path path, Integer version, Integer indent) {
        this.path = path;
        this.version = version;
        this.indent = indent;
    }

    /** The file this store keeps. */
    public Path path() {
        return path;
    }

    /** How many loads found a file that could not be used. */
    public int corrupt() {
        return corrupt;
    }

    /** Count a file that parsed but did not hold what its reader needed. */
    public void noteCorrupt() {
        corrupt++;
    }

    /** The stored mapping, or empty when there is nothing usable. */
    public Optional<Map<String, Object>> load() {
        ReadResult found = JsonFiles.readJson(path, version);
        if (found.corrupt()) {
            corrupt++;
        }
        return found.payload();
    }

    /** Write the mapping, stamped with the version when the store has one. */
    public Path save(Map<String, ?> payload) {
        Map<String, Object> body = new LinkedHashMap<>(payload);
        if (version != null) {
            body.put("version", version);
        }
        return JsonFiles.writeAtomic(path, body, indent);
    }

    /** Remove the file. Returns whether there was one. */
    public boolean forget() {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }
}
