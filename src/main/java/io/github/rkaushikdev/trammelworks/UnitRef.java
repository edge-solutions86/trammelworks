package io.github.rkaushikdev.trammelworks;

/**
 * A running container, identified the way a site controller sees one.
 *
 * <p>{@code attempt} is the restart counter. The pump bridge restarts on every dispenser reset, so
 * the same unit name covers many containers in a day and the attempt is what separates their logs.
 *
 * @param site the site id, normalized
 * @param unit the service name, normalized
 * @param containerId the short container id
 * @param attempt the restart counter
 */
public record UnitRef(String site, String unit, String containerId, int attempt) {

    /** Normalizes all three names and refuses a negative attempt. */
    public UnitRef {
        site = Identity.parseSite(site);
        unit = Identity.parseUnit(unit);
        containerId = Identity.shortId(containerId);
        if (attempt < 0) {
            throw new ConfigError("negative restart attempt", Integer.toString(attempt));
        }
    }

    /** The first run of a container. */
    public UnitRef(String site, String unit, String containerId) {
        this(site, unit, containerId, 0);
    }

    /** The stable path safe name used for spool segments and checkpoints. */
    public String slug() {
        return site + "_" + unit + "_" + attempt;
    }

    @Override
    public String toString() {
        return site + "/" + unit + "#" + attempt + "(" + containerId + ")";
    }
}
