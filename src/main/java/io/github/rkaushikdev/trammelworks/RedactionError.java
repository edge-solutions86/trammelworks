package io.github.rkaushikdev.trammelworks;

/** A redaction rule could not be applied, so the record must not ship. */
public class RedactionError extends StanchionError {

    /** The stable code for this kind of failure. */
    public static final String CODE = "stanchion.redaction";

    /** An error with no detail. */
    public RedactionError(String message) {
        super(message);
    }

    /** An error with an optional detail, which may be {@code null}. */
    public RedactionError(String message, String detail) {
        super(message, detail);
    }

    /** An error with an optional detail and the failure that caused it. */
    public RedactionError(String message, String detail, Throwable cause) {
        super(message, detail, cause);
    }

    @Override
    public String code() {
        return CODE;
    }
}
