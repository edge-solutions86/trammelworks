package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.JsonStore;
import io.github.rkaushikdev.trammelworks.Sections;
import io.github.rkaushikdev.trammelworks.ShipError;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/** The acknowledgement ledger on disk, written whole and atomically. */
public final class AckStore {

    /** The stored form's version. */
    public static final int FORMAT_VERSION = 1;

    /** The ledger's file name. */
    public static final String FILENAME = "acks.json";

    private final Path directory;
    private final JsonStore store;

    /** A store in {@code directory}. */
    public AckStore(Path directory) {
        this.directory = directory;
        this.store = new JsonStore(directory.resolve(FILENAME), FORMAT_VERSION, 1);
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

    /** Read the ledger, treating a damaged one as empty. */
    public AckLedger load() {
        Optional<Map<String, Object>> payload = store.load();
        if (payload.isEmpty()) {
            return new AckLedger();
        }
        AckLedger ledger = new AckLedger();
        try {
            Object raw = payload.get().get("marks");
            List<?> items = raw instanceof List<?> list ? list : List.of();
            for (Object item : items) {
                ledger.put(Watermark.fromDict(Sections.mapping(item, "watermark is missing a field", null)));
            }
        } catch (ShipError | ConfigError exc) {
            store.noteCorrupt();
            return new AckLedger();
        }
        return ledger;
    }

    /** Write the ledger, segments in name order. */
    public Path save(AckLedger ledger) {
        List<Object> marks = new ArrayList<>();
        for (String name : new TreeSet<>(ledger.marks().keySet())) {
            marks.add(ledger.marks().get(name).asDict());
        }
        return store.save(Map.of("marks", marks));
    }
}
