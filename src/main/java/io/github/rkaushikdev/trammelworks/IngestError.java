package io.github.rkaushikdev.trammelworks;

/** A reader could not make progress against a container log file. */
public class IngestError extends StanchionError {

    /** The stable code for this kind of failure. */
    public static final String CODE = "stanchion.ingest";

    /** An error with no detail. */
    public IngestError(String message) {
        super(message);
    }

    /** An error with an optional detail, which may be {@code null}. */
    public IngestError(String message, String detail) {
        super(message, detail);
    }

    /** An error with an optional detail and the failure that caused it. */
    public IngestError(String message, String detail, Throwable cause) {
        super(message, detail, cause);
    }

    @Override
    public String code() {
        return CODE;
    }
}
