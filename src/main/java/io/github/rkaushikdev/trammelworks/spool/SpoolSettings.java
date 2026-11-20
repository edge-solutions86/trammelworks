package io.github.rkaushikdev.trammelworks.spool;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Sections;
import io.github.rkaushikdev.trammelworks.SpoolError;
import io.github.rkaushikdev.trammelworks.Units;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The spool section of a site config, already checked against itself.
 *
 * <p>The checks here catch a config that reads fine piece by piece and cannot work: a segment
 * larger than the usable budget, a reserve so large nothing is usable, a unit assigned to a class
 * nobody defined. They only fail against each other, which is why they are checked where the
 * pieces meet.
 *
 * @param directory where segments live
 * @param budgetBytes the whole disk budget
 * @param segmentBytes the size a segment is sealed at
 * @param reserveRatio the part of the budget kept back for eviction to work in
 * @param syncEvery force to disk every this many frames, or 0 to leave it to the OS
 * @param compress whether frames are deflated when that helps
 * @param priority the site's priority table, when it has its own
 */
public record SpoolSettings(
        String directory,
        long budgetBytes,
        long segmentBytes,
        double reserveRatio,
        int syncEvery,
        boolean compress,
        Optional<PriorityTable> priority) {

    /** Where a commissioned box keeps its spool. */
    public static final String DEFAULT_DIRECTORY = "/var/lib/stanchion/spool";

    /** The default budget. */
    public static final long DEFAULT_BUDGET = 512L * 1024 * 1024;

    /** The default reserve. */
    public static final double DEFAULT_RESERVE = 0.1;

    /** By default the OS decides when to commit. */
    public static final int DEFAULT_SYNC_EVERY = 0;

    static final Set<String> KEYS =
            Set.of("directory", "budget", "segment", "reserve_ratio", "sync_every", "compress", "priority");

    /** Checks the pieces against each other. */
    public SpoolSettings {
        if (syncEvery < 0) {
            throw new ConfigError("sync_every cannot be negative", Integer.toString(syncEvery));
        }
        Budget budget;
        try {
            budget = new Budget(budgetBytes, reserveRatio);
        } catch (SpoolError exc) {
            throw new ConfigError(exc.message(), exc.detail().orElse(null));
        }
        if (segmentBytes <= 0) {
            throw new ConfigError("segment size must be positive", Long.toString(segmentBytes));
        }
        if (segmentBytes > budget.usableBytes()) {
            throw new ConfigError(
                    "the segment size does not fit inside the usable budget",
                    "segment " + Units.formatBytes(segmentBytes) + " > usable "
                            + Units.formatBytes(budget.usableBytes()));
        }
    }

    /** The defaults. */
    public SpoolSettings() {
        this(builder());
    }

    private SpoolSettings(Builder from) {
        this(
                from.directory,
                from.budgetBytes,
                from.segmentBytes,
                from.reserveRatio,
                from.syncEvery,
                from.compress,
                from.priority);
    }

    /** The defaults, at another directory. */
    public static SpoolSettings at(String directory) {
        return builder().directory(directory).build();
    }

    /** A builder starting from the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** The budget object these settings describe. */
    public Budget budget() {
        return new Budget(budgetBytes, reserveRatio);
    }

    /** The site's priority table, or the built in one. */
    public PriorityTable table() {
        return priority.orElseGet(PriorityTable::new);
    }

    /** The directory as a path. */
    public Path path() {
        return Path.of(directory);
    }

    /** One line, in the units an engineer thinks in. */
    public String describe() {
        return "spool " + directory + " budget " + Units.formatBytes(budgetBytes) + " usable "
                + Units.formatBytes(budget().usableBytes()) + " segments " + Units.formatBytes(segmentBytes);
    }

    /** Build the settings from the config block. */
    public static SpoolSettings spoolSettingsFromMapping(Map<String, ?> raw) {
        Sections.checkKeys(raw, KEYS, "spool");
        Builder built = builder();
        if (raw.containsKey("directory")) {
            built.directory(Coerce.text(raw.get("directory")));
        }
        if (raw.containsKey("budget")) {
            built.budgetBytes(Units.parseBytes(raw.get("budget")));
        }
        if (raw.containsKey("segment")) {
            built.segmentBytes(Units.parseBytes(raw.get("segment")));
        }
        if (raw.containsKey("reserve_ratio")) {
            built.reserveRatio(Coerce.toDouble(raw.get("reserve_ratio")));
        }
        if (raw.containsKey("sync_every")) {
            built.syncEvery(Coerce.toInt(raw.get("sync_every")));
        }
        if (raw.containsKey("compress")) {
            built.compress(Coerce.toBool(raw.get("compress")));
        }
        if (raw.get("priority") != null) {
            built.priority(PriorityTable.tableFromMapping(Sections.section(raw, "priority", "spool")));
        }
        return built.build();
    }

    /** Settings built a field at a time, starting from the defaults. */
    public static final class Builder {
        private String directory = DEFAULT_DIRECTORY;
        private long budgetBytes = DEFAULT_BUDGET;
        private long segmentBytes = Segments.DEFAULT_SEGMENT_BYTES;
        private double reserveRatio = DEFAULT_RESERVE;
        private int syncEvery = DEFAULT_SYNC_EVERY;
        private boolean compress = true;
        private Optional<PriorityTable> priority = Optional.empty();

        private Builder() {}

        /** Where segments live. */
        public Builder directory(String value) {
            directory = value;
            return this;
        }

        /** The whole budget. */
        public Builder budgetBytes(long value) {
            budgetBytes = value;
            return this;
        }

        /** The sealing size. */
        public Builder segmentBytes(long value) {
            segmentBytes = value;
            return this;
        }

        /** The reserve. */
        public Builder reserveRatio(double value) {
            reserveRatio = value;
            return this;
        }

        /** The forced sync interval. */
        public Builder syncEvery(int value) {
            syncEvery = value;
            return this;
        }

        /** Whether to deflate. */
        public Builder compress(boolean value) {
            compress = value;
            return this;
        }

        /** The site's own priority table. */
        public Builder priority(PriorityTable value) {
            priority = Optional.of(value);
            return this;
        }

        /** The checked settings. */
        public SpoolSettings build() {
            return new SpoolSettings(this);
        }
    }
}
