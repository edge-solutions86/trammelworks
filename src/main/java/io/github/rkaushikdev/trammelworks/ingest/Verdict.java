package io.github.rkaushikdev.trammelworks.ingest;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What the confinement check decided about one path.
 *
 * @param path the path as the walk found it
 * @param resolved where it really is, when that could be worked out
 * @param refusal why it will not be read, or empty when it may be
 */
public record Verdict(Path path, Optional<Path> resolved, Optional<Refusal> refusal) {

    /** A refusal for a path that could not be resolved. */
    public static Verdict refused(Path path, Refusal refusal) {
        return new Verdict(path, Optional.empty(), Optional.of(refusal));
    }

    /** Whether the path may be read. */
    public boolean ok() {
        return refusal.isEmpty();
    }

    @Override
    public String toString() {
        return refusal.map(reason -> path + " refused: " + reason).orElse(path + " ok");
    }
}
