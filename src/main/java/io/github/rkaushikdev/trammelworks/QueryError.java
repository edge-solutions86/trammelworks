package io.github.rkaushikdev.trammelworks;

/** A filter expression is not valid. */
public class QueryError extends StanchionError {

    /** The stable code for this kind of failure. */
    public static final String CODE = "stanchion.query";

    /** An error with no detail. */
    public QueryError(String message) {
        super(message);
    }

    /** An error with an optional detail, which may be {@code null}. */
    public QueryError(String message, String detail) {
        super(message, detail);
    }

    /** An error with an optional detail and the failure that caused it. */
    public QueryError(String message, String detail, Throwable cause) {
        super(message, detail, cause);
    }

    @Override
    public String code() {
        return CODE;
    }
}
