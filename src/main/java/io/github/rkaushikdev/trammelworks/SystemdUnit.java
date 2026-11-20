package io.github.rkaushikdev.trammelworks;

/**
 * One generated systemd unit file and where it belongs.
 *
 * @param name the file name
 * @param body the file's text
 */
public record SystemdUnit(String name, String body) {

    /** Where the unit is installed. */
    public String path() {
        return Deploy.UNIT_DIRECTORY + "/" + name;
    }

    @Override
    public String toString() {
        return name;
    }
}
