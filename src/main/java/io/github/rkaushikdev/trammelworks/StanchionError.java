package io.github.rkaushikdev.trammelworks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The base of every failure a site controller can hit.
 *
 * <p>Each type carries a short stable {@link #code()}, because the only channel back from an
 * unattended forecourt is a status line in a nightly summary, and a free text message is useless
 * for grouping ten thousand of them. {@link #getMessage()} is the operator facing line: the message,
 * followed by the detail in brackets when there is one.
 */
public class StanchionError extends RuntimeException {

    /** The code of the base type. */
    public static final String CODE = "stanchion.error";

    private final String message;
    private final String detail;

    /** An error with no detail. */
    public StanchionError(String message) {
        this(message, (String) null);
    }

    /** An error with an optional detail, which may be {@code null}. */
    public StanchionError(String message, String detail) {
        super(render(message, detail));
        this.message = message;
        this.detail = detail;
    }

    /** An error with an optional detail and the failure that caused it. */
    public StanchionError(String message, String detail, Throwable cause) {
        super(render(message, detail), cause);
        this.message = message;
        this.detail = detail;
    }

    private static String render(String message, String detail) {
        return detail == null || detail.isEmpty() ? message : message + " (" + detail + ")";
    }

    /** The stable code the estate groups by. */
    public String code() {
        return CODE;
    }

    /** The message without the detail. */
    public String message() {
        return message;
    }

    /** The detail, when one was given. */
    public Optional<String> detail() {
        return Optional.ofNullable(detail);
    }

    /** Flatten to the shape the nightly summary uploads. */
    public Map<String, String> asDict() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("code", code());
        out.put("message", message);
        if (detail != null) {
            out.put("detail", detail);
        }
        return out;
    }
}
