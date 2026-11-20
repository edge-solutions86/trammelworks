package io.github.rkaushikdev.trammelworks;

/** A retention decision conflicts with a hold or a scheme floor. */
public class RetentionError extends StanchionError {

    /** The stable code for this kind of failure. */
    public static final String CODE = "stanchion.retention";

    /** An error with no detail. */
    public RetentionError(String message) {
        super(message);
    }

    /** An error with an optional detail, which may be {@code null}. */
    public RetentionError(String message, String detail) {
        super(message, detail);
    }

    /** An error with an optional detail and the failure that caused it. */
    public RetentionError(String message, String detail, Throwable cause) {
        super(message, detail, cause);
    }

    @Override
    public String code() {
        return CODE;
    }
}
