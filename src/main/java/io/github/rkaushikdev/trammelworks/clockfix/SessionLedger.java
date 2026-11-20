package io.github.rkaushikdev.trammelworks.clockfix;

import io.github.rkaushikdev.trammelworks.ConfigError;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Every boot the box has had that we still know about, oldest first. */
public final class SessionLedger {

    private final Map<String, BootSession> sessions = new HashMap<>();
    private final List<String> order = new ArrayList<>();

    /** The session, created the first time this boot is seen. */
    public BootSession session(String bootId) {
        String cleaned = BootSession.parseBootId(bootId);
        BootSession found = sessions.get(cleaned);
        if (found == null) {
            found = new BootSession(cleaned);
            sessions.put(cleaned, found);
            order.add(cleaned);
        }
        return found;
    }

    /** Put a session in, as the newest one, replacing any with the same boot id. */
    void put(BootSession session) {
        if (sessions.put(session.bootId(), session) == null) {
            order.add(session.bootId());
        }
    }

    /** Replace the order, when it names exactly the sessions held. */
    void reorder(List<String> stated) {
        order.clear();
        order.addAll(stated);
    }

    /** Whether a session is held. */
    public boolean has(String bootId) {
        return sessions.containsKey(bootId);
    }

    /** File a correction against its own session. */
    public void addSync(SyncEvent event) {
        session(event.bootId()).addSync(event);
    }

    /** The boot ids, oldest first. */
    public List<String> order() {
        return List.copyOf(order);
    }

    /** The most recently seen boot, which is the one running now. */
    public Optional<BootSession> current() {
        return order.isEmpty() ? Optional.empty() : Optional.of(sessions.get(order.get(order.size() - 1)));
    }

    /** How many times the box has restarted inside what we remember. */
    public int reboots() {
        return Math.max(0, order.size() - 1);
    }

    /** Drop the oldest sessions. Returns how many went. */
    public int forgetAllBut(int keep) {
        if (keep < 1) {
            throw new ConfigError("must keep at least one session", Integer.toString(keep));
        }
        int removed = 0;
        while (order.size() > keep) {
            sessions.remove(order.remove(0));
            removed++;
        }
        return removed;
    }
}
