package io.github.rkaushikdev.trammelworks;

/** The site configuration is missing, malformed or self contradictory. */
public class ConfigError extends StanchionError {

    /** The stable code for this kind of failure. */
    public static final String CODE = "stanchion.config";

    /** An error with no detail. */
    public ConfigError(String message) {
        super(message);
    }

    /** An error with an optional detail, which may be {@code null}. */
    public ConfigError(String message, String detail) {
        super(message, detail);
    }

    /** An error with an optional detail and the failure that caused it. */
    public ConfigError(String message, String detail, Throwable cause) {
        super(message, detail, cause);
    }

    @Override
    public String code() {
        return CODE;
    }
}
