package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Layout;
import io.github.rkaushikdev.trammelworks.LogPathInfo;
import io.github.rkaushikdev.trammelworks.LogPaths;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.PosixPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Finding the files worth following.
 *
 * <p>The log root holds more than the containers we care about: the runtime's own files, rotated
 * copies of everything, and on an upgraded box a second layout holding the same containers under
 * different names. Discovery walks and classifies, drops what is not a live container log saying
 * why, and collapses the duplicates a two layout box presents. The unit filter is applied last,
 * because the counts in the nightly summary are how anyone notices that a unit stopped logging.
 */
public final class Discover {

    /** Which layout wins when two describe the same container. */
    public static final List<Layout> LAYOUT_PREFERENCE = List.of(Layout.CRI, Layout.FLAT, Layout.DOCKER);

    /** Orders paths component by component, the way the reference sorts them. */
    public static final Comparator<Path> PATH_ORDER = (left, right) -> {
        int count = Math.min(left.getNameCount(), right.getNameCount());
        for (int index = 0; index < count; index++) {
            int found = left.getName(index)
                    .toString()
                    .compareTo(right.getName(index).toString());
            if (found != 0) {
                return found;
            }
        }
        return Integer.compare(left.getNameCount(), right.getNameCount());
    };

    private Discover() {}

    /**
     * Every regular file under the root, in a stable order. Links to files are listed (and then
     * judged by confinement); links to directories are not descended into.
     */
    public static List<Path> walk(Path root) {
        if (!Files.exists(root)) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        collect(root, found);
        found.sort(PATH_ORDER);
        return found.stream().filter(Files::isRegularFile).toList();
    }

    private static void collect(Path directory, List<Path> out) {
        List<Path> children;
        try (Stream<Path> listing = Files.list(directory)) {
            children = listing.toList();
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        for (Path child : children) {
            out.add(child);
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                collect(child, out);
            }
        }
    }

    /**
     * Found paths split into candidates and the ones to leave alone.
     *
     * @param candidates live container logs
     * @param skipped everything else, with why
     */
    public record Classified(List<LogPathInfo> candidates, List<Skipped> skipped) {}

    /** Split found paths into candidates and the ones to leave alone. */
    public static Classified classify(List<Path> paths) {
        List<LogPathInfo> candidates = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (Path path : paths) {
            LogPathInfo info;
            try {
                info = LogPaths.parseLogPath(PosixPath.asPosix(path));
            } catch (ParseError exc) {
                skipped.add(new Skipped(PosixPath.asPosix(path), SkipReason.UNPARSEABLE));
                continue;
            }
            if (info.compressed()) {
                skipped.add(new Skipped(info.path(), SkipReason.COMPRESSED));
                continue;
            }
            if (info.rotation() != 0) {
                skipped.add(new Skipped(info.path(), SkipReason.ROTATED));
                continue;
            }
            candidates.add(info);
        }
        return new Classified(candidates, skipped);
    }

    private static final Comparator<LogPathInfo> RANK = Comparator.comparingInt(
                    (LogPathInfo info) -> LAYOUT_PREFERENCE.indexOf(info.layout()))
            .thenComparingInt(info -> -info.attempt().orElse(0));

    /**
     * The files kept, one per unit, and the duplicates dropped.
     *
     * @param kept one file per unit, sorted by unit
     * @param losers the duplicates
     */
    public record Deduplicated(List<LogPathInfo> kept, List<Skipped> losers) {}

    /** Keep one file per unit when two layouts describe the same container. */
    public static Deduplicated deduplicate(List<LogPathInfo> candidates) {
        Map<String, LogPathInfo> best = new LinkedHashMap<>();
        List<Skipped> losers = new ArrayList<>();
        for (LogPathInfo info : candidates) {
            LogPathInfo current = best.get(info.unit());
            if (current == null) {
                best.put(info.unit(), info);
                continue;
            }
            if (RANK.compare(info, current) < 0) {
                best.put(info.unit(), info);
                losers.add(new Skipped(current.path(), SkipReason.DUPLICATE));
            } else {
                losers.add(new Skipped(info.path(), SkipReason.DUPLICATE));
            }
        }
        return new Deduplicated(List.copyOf(new TreeMap<>(best).values()), losers);
    }

    /** Walk the log root and follow everything in it. */
    public static Discovery discover(Path root) {
        return discover(root, List.of());
    }

    /**
     * Walk the log root and decide what to follow. The confinement check comes before anything
     * reads a byte, because parsing a link out of the root means opening what it points at.
     */
    public static Discovery discover(Path root, List<String> units) {
        Confinement fence = new Confinement(root);
        Confinement.Split split = fence.keep(walk(root));
        Classified classified = classify(split.kept());
        List<Skipped> skipped = new ArrayList<>(classified.skipped());
        for (Verdict item : split.refused()) {
            skipped.add(new Skipped(PosixPath.asPosix(item.path()), SkipReason.OUTSIDE));
        }
        Deduplicated deduplicated = deduplicate(classified.candidates());
        skipped.addAll(deduplicated.losers());
        List<LogPathInfo> kept = deduplicated.kept();
        if (!units.isEmpty()) {
            Set<String> wanted = Set.copyOf(units);
            List<LogPathInfo> selected = new ArrayList<>();
            for (LogPathInfo info : kept) {
                if (wanted.contains(info.unit())) {
                    selected.add(info);
                } else {
                    skipped.add(new Skipped(info.path(), SkipReason.NOT_SELECTED));
                }
            }
            kept = selected;
        }
        return new Discovery(kept, skipped, split.refused());
    }
}
