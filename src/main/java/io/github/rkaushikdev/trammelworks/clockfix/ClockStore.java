package io.github.rkaushikdev.trammelworks.clockfix;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.JsonFiles;
import io.github.rkaushikdev.trammelworks.JsonStore;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.Sections;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the session ledger across a restart: one small JSON file holding every boot we still
 * remember, written whole because a partial ledger is a wrong ledger rather than a smaller one.
 * Old sessions are pruned on save, since a boot from three weeks ago cannot help correct anything
 * still in the spool.
 */
public final class ClockStore {

    /** The stored form's version. */
    public static final int FORMAT_VERSION = 1;

    /** How many sessions are kept by default. */
    public static final int DEFAULT_KEEP_SESSIONS = 8;

    /** The ledger's file name. */
    public static final String FILENAME = "clock.json";

    private final Path directory;
    private final int keepSessions;
    private final JsonStore store;

    /** A store keeping the default number of sessions. */
    public ClockStore(Path directory) {
        this(directory, DEFAULT_KEEP_SESSIONS);
    }

    /** A store keeping {@code keepSessions} sessions. */
    public ClockStore(Path directory, int keepSessions) {
        if (keepSessions < 1) {
            throw new ConfigError("keep_sessions must be at least 1", Integer.toString(keepSessions));
        }
        this.directory = directory;
        this.keepSessions = keepSessions;
        this.store = new JsonStore(directory.resolve(FILENAME), null, 1);
    }

    /** Where the ledger lives. */
    public Path directory() {
        return directory;
    }

    /** The ledger's file. */
    public Path path() {
        return store.path();
    }

    /** How many loads found a ledger that could not be used. */
    public int corrupt() {
        return store.corrupt();
    }

    /** Read the ledger, treating a damaged one as an empty one. */
    public SessionLedger load() {
        Optional<Map<String, Object>> payload = store.load();
        if (payload.isEmpty()) {
            return new SessionLedger();
        }
        try {
            return ledgerFromDict(payload.get());
        } catch (ConfigError exc) {
            store.noteCorrupt();
            return new SessionLedger();
        }
    }

    /** Prune, then write the whole ledger atomically. */
    public Path save(SessionLedger ledger) {
        ledger.forgetAllBut(keepSessions);
        return store.save(ledgerToDict(ledger));
    }

    /** One sync's stored form. */
    public static Map<String, Object> syncToDict(SyncEvent event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("boot_id", event.bootId());
        out.put("monotonic_ns", event.monotonicNs());
        out.put("before", event.before().isoformat());
        out.put("after", event.after().isoformat());
        out.put("source", event.source());
        return out;
    }

    private static Object field(Map<String, ?> payload, String name, String refusal) {
        if (!payload.containsKey(name)) {
            throw new ConfigError(refusal, io.github.rkaushikdev.trammelworks.Repr.of(name));
        }
        return payload.get(name);
    }

    /** Read one sync's stored form. */
    public static SyncEvent syncFromDict(Map<String, ?> payload) {
        String refusal = "sync event is missing a field";
        try {
            return new SyncEvent(
                    Coerce.text(field(payload, "boot_id", refusal)),
                    Coerce.toLong(field(payload, "monotonic_ns", refusal)),
                    NanoTime.parse(Coerce.text(field(payload, "before", refusal))),
                    NanoTime.parse(Coerce.text(field(payload, "after", refusal))),
                    Coerce.text(payload.containsKey("source") ? payload.get("source") : "ntp"));
        } catch (ParseError exc) {
            throw new ConfigError(refusal, exc.getMessage());
        }
    }

    /** One session's stored form. */
    public static Map<String, Object> sessionToDict(BootSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("boot_id", session.bootId());
        out.put("first_monotonic_ns", session.firstMonotonicNs());
        out.put("last_monotonic_ns", session.lastMonotonicNs());
        List<Object> syncs = new ArrayList<>();
        for (SyncEvent event : session.syncs()) {
            syncs.add(syncToDict(event));
        }
        out.put("syncs", syncs);
        return out;
    }

    /** Read one session's stored form. */
    public static BootSession sessionFromDict(Map<String, ?> payload) {
        String refusal = "boot session is missing a field";
        BootSession session = new BootSession(
                Coerce.text(field(payload, "boot_id", refusal)),
                Coerce.toLong(payload.containsKey("first_monotonic_ns") ? payload.get("first_monotonic_ns") : 0),
                Coerce.toLong(payload.containsKey("last_monotonic_ns") ? payload.get("last_monotonic_ns") : 0),
                List.of());
        for (Object raw : list(payload.get("syncs"))) {
            session.addSync(syncFromDict(Sections.mapping(raw, "sync event is missing a field", null)));
        }
        return session;
    }

    private static List<?> list(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> items) {
            return items;
        }
        throw new ConfigError("clock ledger entry is not a list", Coerce.text(raw));
    }

    /** The whole ledger's stored form. */
    public static Map<String, Object> ledgerToDict(SessionLedger ledger) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", FORMAT_VERSION);
        out.put("order", ledger.order());
        List<Object> sessions = new ArrayList<>();
        for (String bootId : ledger.order()) {
            sessions.add(sessionToDict(ledger.session(bootId)));
        }
        out.put("sessions", sessions);
        return out;
    }

    /** Read the whole ledger's stored form. */
    public static SessionLedger ledgerFromDict(Map<String, ?> payload) {
        Object version = payload.get("version");
        if (!JsonFiles.sameNumber(version, FORMAT_VERSION)) {
            throw new ConfigError("unknown clock ledger version", Coerce.text(version));
        }
        SessionLedger ledger = new SessionLedger();
        for (Object raw : list(payload.get("sessions"))) {
            ledger.put(sessionFromDict(Sections.mapping(raw, "boot session is missing a field", null)));
        }
        List<String> stated = new ArrayList<>();
        for (Object item : list(payload.get("order"))) {
            stated.add(Coerce.text(item));
        }
        if (!stated.isEmpty() && new HashSet<>(stated).equals(new HashSet<>(ledger.order()))) {
            ledger.reorder(stated);
        }
        return ledger;
    }
}
