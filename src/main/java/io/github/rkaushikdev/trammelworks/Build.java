package io.github.rkaushikdev.trammelworks;

import io.github.rkaushikdev.trammelworks.archive.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything a support call needs to identify what is running.
 *
 * <p>Several numbers rather than one, because they move independently and the interesting failures
 * are the ones where they disagree. A site running this version against a spool written by an older
 * format is a site whose segments will not read, and the version string alone cannot say so.
 *
 * @param version the release
 * @param spoolFormat the spool frame format this build writes
 * @param archiveSchema the archive schema this build writes
 * @param java the Java runtime's version
 * @param platform the operating system, as {@code linux}, {@code win32} or {@code darwin}
 */
public record Build(String version, int spoolFormat, int archiveSchema, String java, String platform) {

    /** The build's own numbers, with the runtime and platform left blank. */
    public Build() {
        this(Version.VERSION, Version.SPOOL_FORMAT, Schema.SCHEMA_VERSION, "", "");
    }

    /** This build, on this machine. */
    public static Build current() {
        return new Build(
                Version.VERSION,
                Version.SPOOL_FORMAT,
                Schema.SCHEMA_VERSION,
                Runtime.version().toString(),
                platformName(System.getProperty("os.name", "")));
    }

    /** The short platform name support scripts group by. */
    static String platformName(String osName) {
        String lower = osName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("windows")) {
            return "win32";
        }
        if (lower.startsWith("mac") || lower.startsWith("darwin")) {
            return "darwin";
        }
        return lower.replace(" ", "");
    }

    /** The build as one JSON object. */
    public Map<String, Object> asDict() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", version);
        out.put("spool_format", spoolFormat);
        out.put("archive_schema", archiveSchema);
        out.put("java", java);
        out.put("platform", platform);
        return out;
    }

    /** One fact per line, the way somebody pastes it into a ticket. */
    public List<String> lines() {
        return List.of(
                "stanchion    " + version,
                "spool format " + spoolFormat,
                "archive      schema " + archiveSchema,
                "java         " + java,
                "platform     " + platform);
    }

    @Override
    public String toString() {
        return String.join("\n", lines());
    }
}
