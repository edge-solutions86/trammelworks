package io.github.rkaushikdev.trammelworks.clockfix;

import io.github.rkaushikdev.trammelworks.ConfigError;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One run of the box, from power on to power off, identified by the kernel's boot id.
 *
 * <p>Inside one session the monotonic counter is trustworthy: it never jumps and never goes
 * backwards, so the ordering and the intervals inside a session are facts even when the absolute
 * times are wrong. Nothing is corrected here; this records what happened and the drift model
 * decides what a timestamp should have been.
 */
public final class BootSession {

    static final Pattern BOOT_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

    private static final Comparator<SyncEvent> BY_POSITION = Comparator.comparingLong(SyncEvent::monotonicNs);

    private final String bootId;
    private long firstMonotonicNs;
    private long lastMonotonicNs;
    private final List<SyncEvent> syncs = new ArrayList<>();

    /** An empty session. */
    public BootSession(String bootId) {
        this(bootId, 0, 0, List.of());
    }

    /** A session with a known span and syncs, which must all belong to it. */
    public BootSession(String bootId, long firstMonotonicNs, long lastMonotonicNs, List<SyncEvent> syncs) {
        this.bootId = parseBootId(bootId);
        this.firstMonotonicNs = firstMonotonicNs;
        this.lastMonotonicNs = lastMonotonicNs;
        for (SyncEvent event : syncs) {
            check(event);
            this.syncs.add(event);
        }
        this.syncs.sort(BY_POSITION);
    }

    /** Normalize a boot id: dashes out, lower case, 32 hex characters. */
    public static String parseBootId(String raw) {
        String cleaned = raw.strip().toLowerCase(Locale.ROOT).replace("-", "");
        if (!BOOT_ID_PATTERN.matcher(cleaned).find()) {
            throw new ConfigError("malformed boot id", raw);
        }
        return cleaned;
    }

    private void check(SyncEvent event) {
        if (!event.bootId().equals(bootId)) {
            throw new ConfigError("sync event belongs to another boot", event.bootId());
        }
    }

    /** The boot id. */
    public String bootId() {
        return bootId;
    }

    /** The earliest position seen. */
    public long firstMonotonicNs() {
        return firstMonotonicNs;
    }

    /** The latest position seen. */
    public long lastMonotonicNs() {
        return lastMonotonicNs;
    }

    /** Every correction, in monotonic order. */
    public List<SyncEvent> syncs() {
        return Collections.unmodifiableList(syncs);
    }

    /** Note that something happened at this point in the session. */
    public void observe(long monotonicNs) {
        if (monotonicNs < 0) {
            throw new ConfigError("negative monotonic reading", Long.toString(monotonicNs));
        }
        if (lastMonotonicNs == 0 && firstMonotonicNs == 0) {
            firstMonotonicNs = monotonicNs;
        }
        firstMonotonicNs = Math.min(firstMonotonicNs, monotonicNs);
        lastMonotonicNs = Math.max(lastMonotonicNs, monotonicNs);
    }

    /** Record a correction, keeping the list in monotonic order. */
    public void addSync(SyncEvent event) {
        check(event);
        syncs.add(event);
        syncs.sort(BY_POSITION);
        observe(event.monotonicNs());
    }

    /** Whether the clock was corrected at all in this session. */
    public boolean synced() {
        return !syncs.isEmpty();
    }

    /** The span between the first and last positions seen. */
    public long durationNs() {
        return lastMonotonicNs - firstMonotonicNs;
    }

    /**
     * The syncs immediately before (at or before) and after a point in the session.
     *
     * @param before the last sync at or before the point
     * @param after the first sync after it
     */
    public record Bracket(Optional<SyncEvent> before, Optional<SyncEvent> after) {}

    /** The syncs either side of a point. */
    public Bracket bracket(long monotonicNs) {
        SyncEvent before = null;
        SyncEvent after = null;
        for (SyncEvent event : syncs) {
            if (event.monotonicNs() <= monotonicNs) {
                before = event;
            } else if (after == null) {
                after = event;
            }
        }
        return new Bracket(Optional.ofNullable(before), Optional.ofNullable(after));
    }
}
