package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.JsonStore;
import io.github.rkaushikdev.trammelworks.NanoTime;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The meter's state on disk: the one piece of shipper state that must survive a reboot. A
 * forecourt that loses power twice a week would otherwise start every month fresh several times
 * over, and the estate would find out from the bill.
 */
public final class MeterStore {

    /** The meter's file name. */
    public static final String METER_FILENAME = "meter.json";

    /** The stored form's version. */
    public static final int FORMAT_VERSION = 1;

    private final Path directory;
    private final JsonStore store;

    /** A store in {@code directory}. */
    public MeterStore(Path directory) {
        this.directory = directory;
        this.store = new JsonStore(directory.resolve(METER_FILENAME), FORMAT_VERSION, 1);
    }

    /** Where the meter lives. */
    public Path directory() {
        return directory;
    }

    /** The meter's file. */
    public Path path() {
        return store.path();
    }

    /** How many loads found a file that could not be used. */
    public int corrupt() {
        return store.corrupt();
    }

    /**
     * Read the meter, or start a fresh one. The allowance always comes from the config, never from
     * the file: a site whose plan changed must use the new number at once.
     */
    public Meter load(ShipSettings settings) {
        Meter fresh = settings.meter();
        Optional<Map<String, Object>> payload = store.load();
        if (payload.isEmpty()) {
            return fresh;
        }
        Map<String, Object> found = payload.get();
        try {
            if (!found.containsKey("used_bytes") || !found.containsKey("started_at")) {
                throw new ConfigError("meter is missing a field");
            }
            fresh.restore(
                    Coerce.toLong(found.get("used_bytes")),
                    NanoTime.parse(Coerce.text(found.get("started_at"))),
                    Coerce.toInt(found.containsKey("rollovers") ? found.get("rollovers") : 0));
        } catch (ConfigError exc) {
            store.noteCorrupt();
            return settings.meter();
        }
        return fresh;
    }

    /** Write what the meter has spent. */
    public Path save(Meter meter) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("used_bytes", meter.usedBytes());
        out.put("started_at", meter.startedAt().isoformat());
        out.put("rollovers", meter.rollovers());
        out.put("allowance_bytes", meter.allowanceBytes());
        return store.save(out);
    }
}
