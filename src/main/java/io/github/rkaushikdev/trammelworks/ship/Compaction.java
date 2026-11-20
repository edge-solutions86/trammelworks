package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.JsonFiles;
import io.github.rkaushikdev.trammelworks.SpoolError;
import io.github.rkaushikdev.trammelworks.spool.Frame;
import io.github.rkaushikdev.trammelworks.spool.Frames;
import io.github.rkaushikdev.trammelworks.spool.SegmentInfo;
import io.github.rkaushikdev.trammelworks.spool.SegmentRead;
import io.github.rkaushikdev.trammelworks.spool.SegmentReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Getting the acknowledged prefix of a segment off the disk.
 *
 * <p>A segment is deleted whole once every record in it is acknowledged; until then it holds its
 * full size, which on a site shipping slowly is the common case. Compaction rewrites such a
 * segment without the prefix, copying the remaining frames byte for byte so their checksums and
 * compression flags come through untouched. The sequence number stays, so the oldest unshipped
 * payment records keep their place at the front of the queue.
 */
public final class Compaction {

    /** The suffix of a rewrite in progress. */
    public static final String COMPACT_SUFFIX = ".compact";

    /** Below this there is nothing worth the rewrite, whatever the fraction says. */
    public static final long DEFAULT_MIN_RECLAIM = 64L * 1024;

    /** And below this fraction the same is true, whatever the byte count says. */
    public static final double DEFAULT_MIN_FRACTION = 0.25;

    private Compaction() {}

    /**
     * How many bytes the first {@code count} frames occupy, found by walking them: only frame
     * eleven's length can say where frame twelve starts.
     */
    public static long prefixBytes(byte[] data, int count) {
        if (count < 0) {
            throw new SpoolError("cannot measure a negative number of frames", Integer.toString(count));
        }
        Frames.Run run = Frames.iterFrames(data);
        if (run.note().isPresent()) {
            throw new SpoolError("segment will not read whole", run.note().get());
        }
        List<Frame> frames = run.frames();
        if (count > frames.size()) {
            throw new SpoolError("asked for more frames than the segment holds", count + " of " + frames.size());
        }
        if (count == frames.size()) {
            return run.consumed();
        }
        return frames.subList(0, count).stream().mapToLong(Frame::storedSize).sum();
    }

    /** Decide with the default thresholds. */
    public static Optional<CompactionPlan> planOne(SegmentInfo segment, int acked, int total) {
        return planOne(segment, acked, total, DEFAULT_MIN_RECLAIM, DEFAULT_MIN_FRACTION);
    }

    /** Decide whether one segment is worth rewriting. */
    public static Optional<CompactionPlan> planOne(
            SegmentInfo segment, int acked, int total, long minReclaim, double minFraction) {
        if (acked <= 0 || acked >= total) {
            // Nothing acknowledged, or everything: the second one is the shipper's to delete
            // outright, and compacting it to empty would only get in the way of that.
            return Optional.empty();
        }
        byte[] data;
        try {
            data = Files.readAllBytes(segment.path());
        } catch (IOException exc) {
            throw new SpoolError("segment unreadable", JsonFiles.describe(exc));
        }
        long reclaim = prefixBytes(data, acked);
        if (reclaim < minReclaim && reclaim < minFraction * data.length) {
            return Optional.empty();
        }
        return Optional.of(new CompactionPlan(segment, acked, total - acked, reclaim));
    }

    /** Survey with the default thresholds and nothing pinned. */
    public static Survey planCompaction(List<SegmentInfo> segments, AckLedger ledger) {
        return planCompaction(segments, ledger, DEFAULT_MIN_RECLAIM, DEFAULT_MIN_FRACTION, Set.of());
    }

    /**
     * Every segment worth rewriting, biggest saving first. A segment that will not read is reported
     * rather than raised: one bad file must not stop the other nine being rewritten.
     */
    public static Survey planCompaction(
            List<SegmentInfo> segments, AckLedger ledger, long minReclaim, double minFraction, Set<Path> pinned) {
        List<CompactionPlan> plans = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (SegmentInfo segment : segments) {
            if (pinned.contains(segment.path())) {
                continue;
            }
            Watermark mark = ledger.marks().get(segment.name());
            if (mark == null) {
                continue;
            }
            try {
                planOne(segment, mark.acked(), mark.total(), minReclaim, minFraction)
                        .ifPresent(plans::add);
            } catch (SpoolError exc) {
                unreadable.add(segment.name());
            }
        }
        plans.sort(Comparator.comparingLong((CompactionPlan plan) -> -plan.reclaim())
                .thenComparingLong(plan -> plan.segment().sequence()));
        return new Survey(plans, unreadable);
    }

    /**
     * Rewrite a segment without its first {@code drop} frames. Returns the new size. The new bytes
     * go to a sidecar and land with a rename, so a reader sees the whole old file or the whole new
     * one, never half of each.
     */
    public static long rewrite(Path path, int drop) {
        SegmentRead found = SegmentReader.readSegment(path);
        if (found.note().isPresent()) {
            throw new SpoolError(
                    "will not compact a segment that does not read",
                    found.note().get());
        }
        try {
            byte[] data = Files.readAllBytes(path);
            int cut = (int) prefixBytes(data, drop);
            Path sidecar = path.resolveSibling(path.getFileName() + COMPACT_SUFFIX);
            try (FileChannel handle = FileChannel.open(
                    sidecar,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer tail = ByteBuffer.wrap(data, cut, found.consumed() - cut);
                while (tail.hasRemaining()) {
                    handle.write(tail);
                }
                handle.force(true);
            }
            JsonFiles.replace(sidecar, path);
            return (long) found.consumed() - cut;
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    /** Remove any half written compaction a crash left behind. Returns what went. */
    public static List<Path> clearSidecars(Path directory) {
        if (!Files.exists(directory)) {
            return List.of();
        }
        List<Path> removed = new ArrayList<>();
        try (Stream<Path> listing = Files.list(directory)) {
            for (Path path : listing.sorted().toList()) {
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(COMPACT_SUFFIX)) {
                    Files.delete(path);
                    removed.add(path);
                }
            }
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        return removed;
    }
}
