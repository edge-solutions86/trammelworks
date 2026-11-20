package io.github.rkaushikdev.trammelworks;

/** An upload attempt failed in a way the caller has to decide about. */
public class ShipError extends StanchionError {

    /** The stable code for this kind of failure. */
    public static final String CODE = "stanchion.ship";

    /** An error with no detail. */
    public ShipError(String message) {
        super(message);
    }

    /** An error with an optional detail, which may be {@code null}. */
    public ShipError(String message, String detail) {
        super(message, detail);
    }

    /** An error with an optional detail and the failure that caused it. */
    public ShipError(String message, String detail, Throwable cause) {
        super(message, detail, cause);
    }

    @Override
    public String code() {
        return CODE;
    }
}
