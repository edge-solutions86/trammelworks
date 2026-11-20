package io.github.rkaushikdev.trammelworks;

/**
 * Version and build identity.
 *
 * <p>Site controllers are flashed from an image and then live for years without anyone touching
 * them, so the version string has to survive being read out of a log line six months later.
 *
 * <p>One is not a claim that this is finished. It is a promise about the format numbers: from here
 * on a site running an older build can still read what a newer one wrote, or the format number
 * moves and the release notes say what to do about it.
 */
public final class Version {

    /** The release. */
    public static final String VERSION = "1.0.0";

    /**
     * Bumped whenever the on disk spool layout changes in a way an older reader cannot understand.
     * Independent of {@link #VERSION} on purpose.
     */
    public static final int SPOOL_FORMAT = 1;

    private Version() {}

    /** The product and release, as {@code --version} prints them. */
    public static String versionString() {
        return "stanchion " + VERSION;
    }

    /** Identify this build to the collector. */
    public static String userAgent() {
        return "stanchion/" + VERSION + " spool/" + SPOOL_FORMAT;
    }
}
