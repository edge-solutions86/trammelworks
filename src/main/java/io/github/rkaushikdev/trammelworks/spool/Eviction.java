package io.github.rkaushikdev.trammelworks.spool;

import io.github.rkaushikdev.trammelworks.JsonFiles;
import io.github.rkaushikdev.trammelworks.SpoolError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Choosing what to delete when the spool is full.
 *
 * <p>Never a protected class, whatever the pressure; never a segment the shipper has pinned,
 * because it is being uploaded right now; otherwise lowest weight first and oldest first inside a
 * class. A plan says whether it frees what was asked for, because a spool full of protected records
 * cannot be brought back inside its budget by eviction, and the right answer then is to refuse new
 * records rather than delete some of them.
 */
public final class Eviction {

    private Eviction() {}

    /** Plan with nothing pinned. */
    public static EvictionPlan planEviction(List<SegmentInfo> segments, PriorityTable table, long needed) {
        return planEviction(segments, table, needed, Set.of());
    }

    /** Pick segments to delete, worst class and oldest first. */
    public static EvictionPlan planEviction(
            List<SegmentInfo> segments, PriorityTable table, long needed, Set<Path> pinned) {
        if (needed < 0) {
            throw new SpoolError("cannot free a negative number of bytes", Long.toString(needed));
        }
        Set<String> guarded = table.protectedClasses();
        long protectedBytes = segments.stream()
                .filter(item -> guarded.contains(item.klass()))
                .mapToLong(SegmentInfo::size)
                .sum();
        long pinnedBytes = segments.stream()
                .filter(item -> pinned.contains(item.path()))
                .mapToLong(SegmentInfo::size)
                .sum();
        if (needed == 0) {
            return new EvictionPlan(List.of(), 0, protectedBytes, pinnedBytes);
        }
        Map<String, Integer> order = new HashMap<>();
        List<String> evictOrder = table.evictOrder();
        for (int index = 0; index < evictOrder.size(); index++) {
            order.put(evictOrder.get(index), index);
        }
        List<SegmentInfo> candidates = new ArrayList<>(segments.stream()
                .filter(item -> order.containsKey(item.klass()) && !pinned.contains(item.path()))
                .toList());
        candidates.sort(Comparator.comparingInt((SegmentInfo item) -> order.get(item.klass()))
                .thenComparingLong(SegmentInfo::sequence));
        List<SegmentInfo> victims = new ArrayList<>();
        long freed = 0;
        for (SegmentInfo item : candidates) {
            if (freed >= needed) {
                break;
            }
            victims.add(item);
            freed += item.size();
        }
        return new EvictionPlan(victims, needed, protectedBytes, pinnedBytes);
    }

    /** Delete the planned segments, reporting anything that would not go. */
    public static EvictionResult applyEviction(EvictionPlan plan) {
        EvictionResult result = new EvictionResult();
        for (SegmentInfo item : plan.victims()) {
            try {
                if (Files.isDirectory(item.path())) {
                    throw new IOException(item.path() + ": Is a directory");
                }
                Files.delete(item.path());
                result.delete(item);
            } catch (NoSuchFileException exc) {
                // Already gone. Counted as freed because the bytes are not there.
                result.delete(item);
            } catch (IOException exc) {
                result.fail(item, JsonFiles.describe(exc));
            }
        }
        return result;
    }

    /** The most eviction could ever free, given what is protected and pinned. */
    public static long evictableBytes(List<SegmentInfo> segments, PriorityTable table, Set<Path> pinned) {
        Set<String> order = Set.copyOf(table.evictOrder());
        return segments.stream()
                .filter(item -> order.contains(item.klass()) && !pinned.contains(item.path()))
                .mapToLong(SegmentInfo::size)
                .sum();
    }

    /** As {@link #evictableBytes(List, PriorityTable, Set)} with nothing pinned. */
    public static long evictableBytes(List<SegmentInfo> segments, PriorityTable table) {
        return evictableBytes(segments, table, Set.of());
    }
}
