package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.IngestError;
import io.github.rkaushikdev.trammelworks.JsonFiles;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Keeping the walk inside the log root it was pointed at.
 *
 * <p>A symlink under the root is normal on these boxes (the CRI path is frequently a link into the
 * docker tree) and following one blindly is not. A link to {@code /etc/shadow} would be read as a
 * container log; a link to a fifo blocks the collector forever; a link into another site's spool
 * puts one forecourt's records under another's site id. So every candidate is resolved and checked
 * to be under the root, and anything that is not a regular file is refused whatever it resolves
 * to. The root itself is resolved once, because a root reached through a link is fine.
 */
public final class Confinement {

    private final Path root;
    private Path resolvedRoot;

    /** A confinement to {@code root}. */
    public Confinement(Path root) {
        this.root = root;
    }

    /** The root as the filesystem sees it, links followed, resolving as far as it exists. */
    public static Path resolveRoot(Path root) {
        Path absolute = root.toAbsolutePath().normalize();
        Path existing = absolute;
        List<String> rest = new ArrayList<>();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            rest.add(0, existing.getFileName().toString());
            existing = existing.getParent();
        }
        try {
            Path real = existing == null ? absolute.getRoot() : existing.toRealPath();
            for (String part : rest) {
                real = real.resolve(part);
            }
            return real;
        } catch (IOException exc) {
            throw new IngestError("log root cannot be resolved", JsonFiles.describe(exc));
        }
    }

    /** Whether a resolved path sits under a resolved root. */
    public static boolean within(Path root, Path target) {
        Path normalRoot = root.toAbsolutePath().normalize();
        Path normalTarget = target.toAbsolutePath().normalize();
        return !normalTarget.equals(normalRoot) ? normalTarget.startsWith(normalRoot) : true;
    }

    /**
     * Decide whether one path may be read as a container log. Everything is decided on the resolved
     * path: checking the link and then opening the target is the gap the check exists to close.
     */
    public static Verdict check(Path path, Path root) {
        Path resolved;
        try {
            resolved = path.toRealPath();
        } catch (FileSystemException exc) {
            String reason = String.valueOf(exc.getReason()).toLowerCase(Locale.ROOT);
            return Verdict.refused(path, reason.contains("symbolic links") ? Refusal.LOOP : Refusal.UNREADABLE);
        } catch (IOException exc) {
            return Verdict.refused(path, Refusal.UNREADABLE);
        }
        if (!within(root, resolved)) {
            return new Verdict(path, Optional.of(resolved), Optional.of(Refusal.OUTSIDE));
        }
        if (!Files.isRegularFile(resolved)) {
            // A fifo is the one that matters. Reading one never returns, and an unattended site
            // that stops shipping is worse than one that complains.
            return new Verdict(path, Optional.of(resolved), Optional.of(Refusal.NOT_A_FILE));
        }
        return new Verdict(path, Optional.of(resolved), Optional.empty());
    }

    /** The root, resolved once and kept. */
    public Path prepare() {
        if (resolvedRoot == null) {
            resolvedRoot = resolveRoot(root);
        }
        return resolvedRoot;
    }

    /** Check one path against the prepared root. */
    public Verdict check(Path path) {
        return check(path, prepare());
    }

    /**
     * What a walk may read and what it may not.
     *
     * @param kept the paths that may be read, in order
     * @param refused the verdicts on the rest
     */
    public record Split(List<Path> kept, List<Verdict> refused) {

        /** Copies the lists. */
        public Split {
            kept = List.copyOf(kept);
            refused = List.copyOf(refused);
        }
    }

    /** Split a walk into what may be read and what was refused. */
    public Split keep(List<Path> paths) {
        List<Path> kept = new ArrayList<>();
        List<Verdict> refused = new ArrayList<>();
        for (Path path : paths) {
            Verdict verdict = check(path);
            if (verdict.ok()) {
                kept.add(path);
            } else {
                refused.add(verdict);
            }
        }
        return new Split(kept, refused);
    }
}
