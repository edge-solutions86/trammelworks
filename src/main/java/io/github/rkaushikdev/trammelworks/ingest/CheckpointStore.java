package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.IngestError;
import io.github.rkaushikdev.trammelworks.JsonStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One small file per followed log file, remembering where the reader got to.
 *
 * <p>A site controller reboots whenever the forecourt loses power. Without a checkpoint every
 * reboot either replays the whole log root, which blows the LTE allowance in an afternoon, or skips
 * to the end, which loses the reason the box rebooted. Writes are atomic, because a checkpoint half
 * written by a power cut looks valid and points somewhere wrong.
 */
public final class CheckpointStore {

    /** The suffix every checkpoint file carries. */
    public static final String SUFFIX = ".ckpt";

    static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]*$");

    private final Path directory;
    private int corrupt;

    /** A store in {@code directory}. */
    public CheckpointStore(Path directory) {
        this.directory = directory;
    }

    /** Keys become filenames, so they may not wander out of the directory. */
    public static String checkKey(String key) {
        if (!KEY_PATTERN.matcher(key).find()) {
            throw new IngestError("malformed checkpoint key", key);
        }
        return key;
    }

    /** Where the checkpoints live. */
    public Path directory() {
        return directory;
    }

    /** How many loads found a checkpoint that could not be used. */
    public int corrupt() {
        return corrupt;
    }

    private Path target(String key) {
        return directory.resolve(checkKey(key) + SUFFIX);
    }

    private JsonStore store(String key) {
        return new JsonStore(target(key), Checkpoint.FORMAT_VERSION);
    }

    /** Read a checkpoint, treating a damaged one as absent. */
    public Optional<Checkpoint> load(String key) {
        JsonStore found = store(key);
        Optional<Map<String, Object>> payload = found.load();
        corrupt += found.corrupt();
        if (payload.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Checkpoint.fromDict(payload.get()));
        } catch (IngestError exc) {
            corrupt++;
            return Optional.empty();
        }
    }

    /** Write a checkpoint atomically. */
    public Path save(Checkpoint checkpoint) {
        if (checkpoint.offset() < 0) {
            throw new IngestError("cannot checkpoint a negative offset");
        }
        return store(checkpoint.key()).save(checkpoint.asDict());
    }

    /** Drop a checkpoint. Returns whether there was one. */
    public boolean forget(String key) {
        try {
            return Files.deleteIfExists(target(key));
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    /** Every stored key, sorted. */
    public List<String> keys() {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (Stream<Path> found = Files.list(directory)) {
            return found.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(SUFFIX))
                    .map(name -> name.substring(0, name.length() - SUFFIX.length()))
                    .sorted()
                    .toList();
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    /** Remove checkpoints for files nobody is following any more. */
    public int sweep(Set<String> live) {
        int removed = 0;
        for (String key : keys()) {
            if (!live.contains(key) && forget(key)) {
                removed++;
            }
        }
        return removed;
    }
}
