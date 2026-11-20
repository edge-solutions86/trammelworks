package io.github.rkaushikdev.trammelworks.spool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What an eviction actually deleted, and what would not go. */
public final class EvictionResult {

    /** A segment that could not be deleted, and why. */
    public record Failure(SegmentInfo segment, String reason) {}

    private final List<SegmentInfo> deleted = new ArrayList<>();
    private final List<Failure> failed = new ArrayList<>();
    private long freed;

    void delete(SegmentInfo item) {
        deleted.add(item);
        freed += item.size();
    }

    void fail(SegmentInfo item, String reason) {
        failed.add(new Failure(item, reason));
    }

    /** The segments that went. */
    public List<SegmentInfo> deleted() {
        return Collections.unmodifiableList(deleted);
    }

    /** The segments that would not go. */
    public List<Failure> failed() {
        return Collections.unmodifiableList(failed);
    }

    /** Bytes freed. */
    public long freed() {
        return freed;
    }

    /** How many segments went. */
    public int count() {
        return deleted.size();
    }
}
