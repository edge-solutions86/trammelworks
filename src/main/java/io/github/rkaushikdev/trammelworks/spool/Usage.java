package io.github.rkaushikdev.trammelworks.spool;

import io.github.rkaushikdev.trammelworks.Maps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the spool is holding right now.
 *
 * @param sealedBytes bytes in sealed segments
 * @param openBytes bytes in the segments still being written
 * @param segments how many sealed segments there are
 * @param byClass sealed bytes per priority class
 */
public record Usage(long sealedBytes, long openBytes, int segments, Map<String, Long> byClass) {

    /** Copies the breakdown. */
    public Usage {
        byClass = Maps.frozen(byClass);
    }

    /** Nothing at all. */
    public Usage() {
        this(0, 0, 0, Map.of());
    }

    /** Everything the spool is holding, sealed and open. */
    public long used() {
        return sealedBytes + openBytes;
    }

    /** Sealed bytes in one class. */
    public long ofClass(String klass) {
        return byClass.getOrDefault(klass, 0L);
    }

    /** Add up what is on disk. */
    public static Usage measure(List<SegmentInfo> segments) {
        return measure(segments, 0);
    }

    /** Add up what is on disk, plus what the open segments hold. */
    public static Usage measure(List<SegmentInfo> segments, long openBytes) {
        Map<String, Long> byClass = new LinkedHashMap<>();
        long total = 0;
        for (SegmentInfo item : segments) {
            byClass.merge(item.klass(), item.size(), Long::sum);
            total += item.size();
        }
        return new Usage(total, openBytes, segments.size(), byClass);
    }
}
