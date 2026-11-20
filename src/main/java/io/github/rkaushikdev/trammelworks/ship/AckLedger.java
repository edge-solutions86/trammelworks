package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.ShipError;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every segment the shipper is part way through. A segment whose watermark reaches its record
 * count is done, and only then may eviction have it; everything else is pinned.
 */
public final class AckLedger {

    private final Map<String, Watermark> marks = new LinkedHashMap<>();

    /** The watermarks, by segment name. */
    public Map<String, Watermark> marks() {
        return Collections.unmodifiableMap(marks);
    }

    void put(Watermark mark) {
        marks.put(mark.segment(), mark);
    }

    /** The watermark for a segment, starting one if it is new. */
    public Watermark mark(String segment, int total) {
        Watermark found = marks.get(segment);
        if (found == null) {
            found = new Watermark(segment, total);
            marks.put(segment, found);
        } else if (total < found.total()) {
            // A compaction that landed without getting as far as writing the ledger looks exactly
            // like this. Take the file's word for it and send what is there again.
            found.resize(total);
        } else if (total > found.total()) {
            throw new ShipError(
                    "a segment grew under the watermark", segment + ": " + found.total() + " then " + total);
        }
        return found;
    }

    /** Tell the ledger a segment has been rewritten shorter. */
    public Watermark resize(String segment, int total) {
        Watermark found = marks.get(segment);
        if (found == null) {
            throw new ShipError("resized a segment nobody is shipping", segment);
        }
        found.resize(total);
        return found;
    }

    /** Move a segment's watermark on. */
    public Watermark acknowledge(String segment, int count) {
        Watermark found = marks.get(segment);
        if (found == null) {
            throw new ShipError("acknowledged a segment nobody is shipping", segment);
        }
        found.advance(count);
        return found;
    }

    /** The segments fully acknowledged, sorted. */
    public List<String> doneSegments() {
        return names(true);
    }

    /** The segments still being shipped, sorted. */
    public List<String> pendingSegments() {
        return names(false);
    }

    private List<String> names(boolean done) {
        List<String> out = new ArrayList<>();
        marks.forEach((name, mark) -> {
            if (mark.done() == done) {
                out.add(name);
            }
        });
        out.sort(null);
        return out;
    }

    /** Records still to be acknowledged, everywhere. */
    public long outstanding() {
        return marks.values().stream().mapToLong(Watermark::outstanding).sum();
    }

    /** Drop a segment's watermark. Returns whether there was one. */
    public boolean forget(String segment) {
        return marks.remove(segment) != null;
    }

    /** Drop watermarks for segments that are no longer on disk. */
    public int sweep(Set<String> live) {
        List<String> gone = new ArrayList<>();
        for (String name : marks.keySet()) {
            if (!live.contains(name)) {
                gone.add(name);
            }
        }
        gone.forEach(marks::remove);
        return gone.size();
    }
}
