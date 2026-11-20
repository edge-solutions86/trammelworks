package io.github.rkaushikdev.trammelworks;

import io.github.rkaushikdev.trammelworks.ingest.MultilineRule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where the container runtime writes and how it is read.
 *
 * <p>Defaults are chosen for the smallest box in the estate, and every field is validated here, so
 * there is no such thing as a partially valid config.
 */
public record IngestConfig(
        String logRoot,
        Duration pollInterval,
        int readChunk,
        int maxLineBytes,
        List<String> units,
        List<MultilineRule> multiline) {

    /** Where the container runtime writes on a commissioned box. */
    public static final String DEFAULT_LOG_ROOT = "/var/log/containers";

    /** How often the log directory is looked at. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);

    /** How much is read from a file at a time. */
    public static final int DEFAULT_READ_CHUNK = 64 * 1024;

    /** The longest line kept whole. */
    public static final int DEFAULT_MAX_LINE_BYTES = 1024 * 1024;

    /** Validates every field and normalizes the unit filter. */
    public IngestConfig {
        if (!isAbsolutePath(logRoot)) {
            throw new ConfigError("log_root must be an absolute path", logRoot);
        }
        requirePositive("read_chunk", readChunk);
        requirePositive("max_line_bytes", maxLineBytes);
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new ConfigError("poll_interval must be positive");
        }
        if (maxLineBytes < readChunk) {
            throw new ConfigError("max_line_bytes must be at least read_chunk", maxLineBytes + " < " + readChunk);
        }
        units = units.stream().map(Identity::parseUnit).toList();
        multiline = List.copyOf(multiline);
        Set<String> named = new HashSet<>();
        for (MultilineRule rule : multiline) {
            if (!named.add(rule.unit())) {
                throw new ConfigError("two multiline rules name the same unit");
            }
        }
    }

    /** The defaults. */
    public IngestConfig() {
        this(builder());
    }

    private IngestConfig(Builder from) {
        this(from.logRoot, from.pollInterval, from.readChunk, from.maxLineBytes, from.units, from.multiline);
    }

    /** The defaults, reading another directory. */
    public static IngestConfig at(String logRoot) {
        return builder().logRoot(logRoot).build();
    }

    /** A builder starting from the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    private static void requirePositive(String name, long value) {
        if (value <= 0) {
            throw new ConfigError(name + " must be positive", Long.toString(value));
        }
    }

    /**
     * True for a path that is absolute under either convention. A site controller is Linux and every
     * path in a real config is posix, but the commissioning tool runs on the field engineer's Windows
     * laptop and validates the file it is about to push, so a drive rooted path counts as absolute.
     */
    public static boolean isAbsolutePath(String raw) {
        return raw.startsWith("/") || windowsAbsolute(raw);
    }

    private static boolean windowsAbsolute(String raw) {
        String path = raw.replace('/', '\\');
        if (path.startsWith("\\\\")) {
            int start = path.length() >= 8
                            && path.substring(0, 8).toUpperCase(Locale.ROOT).equals("\\\\?\\UNC\\")
                    ? 8
                    : 2;
            int index = path.indexOf('\\', start);
            if (index == -1) {
                return false;
            }
            if (path.indexOf('\\', index + 1) != -1) {
                return true;
            }
            // A bare server and share, //server/share, still gets a root of its own.
            String[] parts = path.split("\\\\", -1);
            return (parts.length == 4 && !"?.".contains(parts[2])) || parts.length == 6;
        }
        if (path.startsWith("\\")) {
            return false;
        }
        int[] points = path.codePoints().limit(3).toArray();
        return points.length == 3 && points[1] == ':' && points[2] == '\\';
    }

    /** Build the section from the config block. Keys this build does not read are ignored. */
    public static IngestConfig ingestFromMapping(Map<String, ?> raw) {
        Builder built = builder();
        if (raw.containsKey("log_root")) {
            built.logRoot(Coerce.text(raw.get("log_root")));
        }
        if (raw.containsKey("poll_interval")) {
            built.pollInterval(Units.parseDuration(raw.get("poll_interval")));
        }
        if (raw.containsKey("read_chunk")) {
            built.readChunk(size(raw.get("read_chunk")));
        }
        if (raw.containsKey("max_line_bytes")) {
            built.maxLineBytes(size(raw.get("max_line_bytes")));
        }
        if (raw.containsKey("units")) {
            built.units(items(raw.get("units")));
        }
        Map<String, Object> rules = Sections.section(raw, "multiline", "ingest");
        built.multiline(List.copyOf(MultilineRule.rulesFromMapping(rules).values()));
        return built.build();
    }

    private static int size(Object raw) {
        long value = Units.parseBytes(raw);
        if (value > Integer.MAX_VALUE) {
            throw new ConfigError("number is out of range", Long.toString(value));
        }
        return (int) value;
    }

    /** The members of a unit filter: a list's items, a mapping's keys, or a bare string's characters. */
    private static List<String> items(Object raw) {
        List<String> out = new ArrayList<>();
        switch (raw) {
            case String text -> text.codePoints().forEach(point -> out.add(Character.toString(point)));
            case Collection<?> values -> values.forEach(value -> out.add(Coerce.text(value)));
            case Map<?, ?> values -> values.keySet().forEach(value -> out.add(Coerce.text(value)));
            case null -> throw new ConfigError("the unit filter must be a list", "None");
            default -> throw new ConfigError("the unit filter must be a list", Coerce.text(raw));
        }
        return out;
    }

    /** An ingest section built a field at a time, starting from the defaults. */
    public static final class Builder {
        private String logRoot = DEFAULT_LOG_ROOT;
        private Duration pollInterval = DEFAULT_POLL_INTERVAL;
        private int readChunk = DEFAULT_READ_CHUNK;
        private int maxLineBytes = DEFAULT_MAX_LINE_BYTES;
        private List<String> units = List.of();
        private List<MultilineRule> multiline = List.of();

        private Builder() {}

        /** The directory the runtime writes to. */
        public Builder logRoot(String value) {
            logRoot = value;
            return this;
        }

        /** How often the directory is looked at. */
        public Builder pollInterval(Duration value) {
            pollInterval = value;
            return this;
        }

        /** Bytes read at a time. */
        public Builder readChunk(int value) {
            readChunk = value;
            return this;
        }

        /** The longest line kept whole. */
        public Builder maxLineBytes(int value) {
            maxLineBytes = value;
            return this;
        }

        /** Only these units are read; empty means all of them. */
        public Builder units(List<String> value) {
            units = List.copyOf(value);
            return this;
        }

        /** The per unit rules for stitching multiline events. */
        public Builder multiline(List<MultilineRule> value) {
            multiline = List.copyOf(value);
            return this;
        }

        /** The validated section. */
        public IngestConfig build() {
            return new IngestConfig(this);
        }
    }
}
