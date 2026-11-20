package io.github.rkaushikdev.trammelworks;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Site, unit and container identity, plus the dedupe key.
 *
 * <p>Shipping is at least once, so the collector sees the same record twice whenever an
 * acknowledgement is lost on a flaky LTE link. Dedupe therefore happens on a key both ends compute
 * from the record alone, which rules out anything drawn from the spool position or the attempt.
 */
public final class Identity {

    /**
     * Forecourt sites are numbered by the estate: two letter country, two to three letter region,
     * four digit site. Anything else is a typo in the commissioning file.
     */
    public static final Pattern SITE_PATTERN = Pattern.compile("^[A-Z]{2}-[A-Z]{2,3}-\\d{4}$");

    /** Container runtimes hand out 64 hex characters. */
    public static final Pattern CONTAINER_ID_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    /** Everything human facing uses the first twelve, which is what the runtime prints too. */
    public static final int SHORT_ID_LENGTH = 12;

    /** A unit is the container's service name. */
    public static final Pattern UNIT_PATTERN = Pattern.compile("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$");

    /** The dedupe key's digest size, in bytes. */
    public static final int KEY_BYTES = 16;

    private static final byte[] SEPARATOR = {0x1f};

    private Identity() {}

    /** Validate and normalize a site identifier. */
    public static String parseSite(String raw) {
        String cleaned = raw.strip().toUpperCase(Locale.ROOT);
        if (!SITE_PATTERN.matcher(cleaned).find()) {
            throw new ConfigError("malformed site id", raw);
        }
        return cleaned;
    }

    /** Validate a unit name. */
    public static String parseUnit(String raw) {
        String cleaned = raw.strip().toLowerCase(Locale.ROOT);
        if (!UNIT_PATTERN.matcher(cleaned).find()) {
            throw new ConfigError("malformed unit name", raw);
        }
        return cleaned;
    }

    /** Shorten a full container id the way the runtime does. */
    public static String shortId(String containerId) {
        String cleaned = containerId.strip().toLowerCase(Locale.ROOT);
        if (!CONTAINER_ID_PATTERN.matcher(cleaned).find()) {
            throw new ConfigError("malformed container id", containerId);
        }
        return cleaned.substring(0, SHORT_ID_LENGTH);
    }

    /**
     * The dedupe key for a record.
     *
     * <p>Built only from fields the collector also receives. The timestamp goes in at full
     * nanosecond precision in UTC so two sites in different offsets agree, and the message is
     * hashed in UTF-8 so an encoding round trip does not change the answer.
     */
    public static String recordKey(LogRecord record) {
        return new Blake2b(KEY_BYTES)
                .update(record.site())
                .update(SEPARATOR)
                .update(record.unit())
                .update(SEPARATOR)
                .update(record.stream().value().getBytes(StandardCharsets.US_ASCII))
                .update(SEPARATOR)
                .update(record.observedAt().isoformat().getBytes(StandardCharsets.US_ASCII))
                .update(SEPARATOR)
                .update(record.message())
                .hexdigest();
    }
}
