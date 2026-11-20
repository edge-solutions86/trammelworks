package io.github.rkaushikdev.trammelworks;

/** The on disk spool is unusable or out of budget. */
public class SpoolError extends StanchionError {

    /** The stable code for this kind of failure. */
    public static final String CODE = "stanchion.spool";

    /** An error with no detail. */
    public SpoolError(String message) {
        super(message);
    }

    /** An error with an optional detail, which may be {@code null}. */
    public SpoolError(String message, String detail) {
        super(message, detail);
    }

    /** An error with an optional detail and the failure that caused it. */
    public SpoolError(String message, String detail, Throwable cause) {
        super(message, detail, cause);
    }

    @Override
    public String code() {
        return CODE;
    }
}
