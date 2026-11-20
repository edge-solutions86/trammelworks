package io.github.rkaushikdev.trammelworks.archive;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.PosixPath;
import io.github.rkaushikdev.trammelworks.Sections;
import io.github.rkaushikdev.trammelworks.Units;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * The {@code archive} section: where the archive lives and how long it keeps things.
 *
 * <p>The archive can be turned off, for unattended sites with no card reader. The cap is not
 * retention: retention says what may be deleted, the cap what has to be, and trimming past
 * retention is opt in and said out loud. And the archive may not live inside the spool, where an
 * eviction sweep could reach the dispute archive.
 *
 * @param enabled whether the site keeps an archive
 * @param path the archive file
 * @param maxBytes the cap on the records' live pages
 * @param maxDeletes the most rows one sweep pass deletes
 * @param sweepEvery how often retention runs
 * @param trimToCap whether records retention would keep may be trimmed to fit the cap
 * @param vacuum whether free pages are given back
 * @param vacuumMinReclaimBytes the smallest saving worth a vacuum
 * @param retention the retention windows
 */
public record ArchiveSettings(
        boolean enabled,
        String path,
        long maxBytes,
        int maxDeletes,
        Duration sweepEvery,
        boolean trimToCap,
        boolean vacuum,
        long vacuumMinReclaimBytes,
        RetentionPolicy retention) {

    /** Where a commissioned box keeps its archive. */
    public static final String DEFAULT_PATH = "/var/lib/stanchion/archive/records.sqlite3";

    /** The default per pass delete cap. */
    public static final int DEFAULT_MAX_DELETES = 50_000;

    /** The default sweep interval. */
    public static final Duration DEFAULT_SWEEP_EVERY = Duration.ofHours(6);

    /** The default cap. */
    public static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024;

    /** The default smallest saving worth a vacuum. */
    public static final long DEFAULT_VACUUM_MIN_RECLAIM = 32L * 1024 * 1024;

    static final Set<String> KEYS = Set.of(
            "enabled",
            "path",
            "max_bytes",
            "max_deletes",
            "sweep_every",
            "trim_to_cap",
            "vacuum",
            "vacuum_min_reclaim",
            "retention");

    /** Checks the numbers. */
    public ArchiveSettings {
        if (maxDeletes < 1) {
            throw new ConfigError("max_deletes must be at least one", Integer.toString(maxDeletes));
        }
        if (maxBytes <= 0) {
            throw new ConfigError("archive max_bytes must be positive", Long.toString(maxBytes));
        }
        if (sweepEvery.isNegative() || sweepEvery.isZero()) {
            throw new ConfigError("sweep_every must be positive");
        }
        if (vacuumMinReclaimBytes < 0) {
            throw new ConfigError("vacuum_min_reclaim cannot be negative", Long.toString(vacuumMinReclaimBytes));
        }
    }

    /** The defaults. */
    public ArchiveSettings() {
        this(builder());
    }

    private ArchiveSettings(Builder from) {
        this(
                from.enabled,
                from.path,
                from.maxBytes,
                from.maxDeletes,
                from.sweepEvery,
                from.trimToCap,
                from.vacuum,
                from.vacuumMinReclaimBytes,
                from.retention);
    }

    /** The defaults, at another path. */
    public static ArchiveSettings at(String path) {
        return builder().path(path).build();
    }

    /** A builder starting from the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** The archive file as a path. */
    public Path file() {
        return Path.of(path);
    }

    /** Refuse an archive that an eviction sweep could reach. */
    public void checkAgainstSpool(String spoolDirectory) {
        PosixPath spool = PosixPath.of(spoolDirectory);
        PosixPath target = PosixPath.of(PosixPath.asPosix(Path.of(path)));
        if (target.parents().contains(spool) || target.parent().equals(spool)) {
            throw new ConfigError("the archive must not live inside the spool directory", path);
        }
    }

    /** One line for the doctor. */
    public String describe() {
        if (!enabled) {
            return "archive off";
        }
        String end = trimToCap ? ", trims to fit" : "";
        return "archive " + path + ", " + retention.describe() + ", cap " + Units.formatBytes(maxBytes) + end;
    }

    /** Build the section from the config block. */
    public static ArchiveSettings archiveSettingsFromMapping(Map<String, ?> raw) {
        Sections.checkKeys(raw, KEYS, "archive");
        Map<String, Object> retentionRaw = Sections.section(raw, "retention", "archive");
        Builder built = builder();
        if (raw.containsKey("enabled")) {
            built.enabled(Coerce.toBool(raw.get("enabled")));
        }
        if (raw.containsKey("path")) {
            built.path(Coerce.text(raw.get("path")));
        }
        if (raw.containsKey("max_bytes")) {
            built.maxBytes(Units.parseBytes(raw.get("max_bytes")));
        }
        if (raw.containsKey("max_deletes")) {
            built.maxDeletes(Coerce.toInt(raw.get("max_deletes")));
        }
        if (raw.containsKey("sweep_every")) {
            built.sweepEvery(Units.parseDuration(raw.get("sweep_every")));
        }
        if (raw.containsKey("trim_to_cap")) {
            built.trimToCap(Coerce.toBool(raw.get("trim_to_cap")));
        }
        if (raw.containsKey("vacuum")) {
            built.vacuum(Coerce.toBool(raw.get("vacuum")));
        }
        if (raw.containsKey("vacuum_min_reclaim")) {
            built.vacuumMinReclaimBytes(Units.parseBytes(raw.get("vacuum_min_reclaim")));
        }
        built.retention(RetentionPolicy.policyFromMapping(retentionRaw));
        return built.build();
    }

    /** Settings built a field at a time, starting from the defaults. */
    public static final class Builder {
        private boolean enabled = true;
        private String path = DEFAULT_PATH;
        private long maxBytes = DEFAULT_MAX_BYTES;
        private int maxDeletes = DEFAULT_MAX_DELETES;
        private Duration sweepEvery = DEFAULT_SWEEP_EVERY;
        private boolean trimToCap;
        private boolean vacuum = true;
        private long vacuumMinReclaimBytes = DEFAULT_VACUUM_MIN_RECLAIM;
        private RetentionPolicy retention = new RetentionPolicy();

        private Builder() {}

        /** Whether the site keeps an archive. */
        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        /** The archive file. */
        public Builder path(String value) {
            path = value;
            return this;
        }

        /** The cap. */
        public Builder maxBytes(long value) {
            maxBytes = value;
            return this;
        }

        /** The per pass delete cap. */
        public Builder maxDeletes(int value) {
            maxDeletes = value;
            return this;
        }

        /** The sweep interval. */
        public Builder sweepEvery(Duration value) {
            sweepEvery = value;
            return this;
        }

        /** Whether to trim past retention to fit. */
        public Builder trimToCap(boolean value) {
            trimToCap = value;
            return this;
        }

        /** Whether to vacuum. */
        public Builder vacuum(boolean value) {
            vacuum = value;
            return this;
        }

        /** The smallest saving worth a vacuum. */
        public Builder vacuumMinReclaimBytes(long value) {
            vacuumMinReclaimBytes = value;
            return this;
        }

        /** The retention windows. */
        public Builder retention(RetentionPolicy value) {
            retention = value;
            return this;
        }

        /** The checked settings. */
        public ArchiveSettings build() {
            return new ArchiveSettings(this);
        }
    }
}
