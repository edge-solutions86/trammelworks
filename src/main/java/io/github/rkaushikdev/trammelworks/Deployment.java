package io.github.rkaushikdev.trammelworks;

/**
 * Everything the unit generator needs that is not in the site config.
 *
 * @param configPath the config file the units run against
 * @param executable the installed command
 * @param user the account the units run as
 * @param group the group the units run as
 * @param serve whether to add the read only query service
 * @param housekeepAt the time of night the housekeeping runs, as {@code HH:MM}
 */
public record Deployment(
        String configPath, String executable, String user, String group, boolean serve, String housekeepAt) {

    /** Refuses relative paths and a time that is not one. */
    public Deployment {
        if (!configPath.startsWith("/")) {
            throw new ConfigError("the config path in a unit must be absolute", configPath);
        }
        if (!executable.startsWith("/")) {
            throw new ConfigError("the executable in a unit must be absolute", executable);
        }
        if (!looksLikeATime(housekeepAt)) {
            throw new ConfigError("housekeep_at must be HH:MM", housekeepAt);
        }
    }

    /** The defaults for a commissioned box. */
    public Deployment() {
        this(
                Deploy.DEFAULT_CONFIG,
                Deploy.DEFAULT_EXECUTABLE,
                Deploy.DEFAULT_USER,
                Deploy.DEFAULT_GROUP,
                false,
                "03:20");
    }

    private static boolean looksLikeATime(String raw) {
        int colon = raw.indexOf(':');
        String hours = colon < 0 ? raw : raw.substring(0, colon);
        String minutes = colon < 0 ? "" : raw.substring(colon + 1);
        if (!digits(hours) || !digits(minutes)) {
            return false;
        }
        return value(hours) <= 23 && value(minutes) <= 59;
    }

    private static boolean digits(String text) {
        return !text.isEmpty() && text.codePoints().allMatch(Character::isDigit);
    }

    private static long value(String text) {
        long out = 0;
        for (int point : text.codePoints().toArray()) {
            out = Math.min(out * 10 + Character.digit(point, 10), Long.MAX_VALUE / 10);
        }
        return out;
    }

    /** The same, with another config path. */
    public Deployment withConfigPath(String value) {
        return new Deployment(value, executable, user, group, serve, housekeepAt);
    }

    /** The same, with another executable. */
    public Deployment withExecutable(String value) {
        return new Deployment(configPath, value, user, group, serve, housekeepAt);
    }

    /** The same, running as another user and group. */
    public Deployment withAccount(String userName, String groupName) {
        return new Deployment(configPath, executable, userName, groupName, serve, housekeepAt);
    }

    /** The same, with or without the query service. */
    public Deployment withServe(boolean value) {
        return new Deployment(configPath, executable, user, group, value, housekeepAt);
    }

    /** The same, housekeeping at another time. */
    public Deployment withHousekeepAt(String value) {
        return new Deployment(configPath, executable, user, group, serve, value);
    }
}
