package io.github.rkaushikdev.trammelworks.service;

import io.github.rkaushikdev.trammelworks.Json;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What goes back: always JSON, including the errors, because a caller parsing a mixture of JSON and
 * HTML error pages ends up guessing, and the one time it guesses wrong is the outage.
 *
 * @param status the HTTP status
 * @param payload the body
 */
public record ServiceResponse(int status, Map<String, Object> payload) {

    /** A refusal carrying a message and whatever else the caller should see. */
    public static ServiceResponse problem(int status, String message, Object... extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", message);
        for (int index = 0; index + 1 < extra.length; index += 2) {
            payload.put((String) extra[index], extra[index + 1]);
        }
        return new ServiceResponse(status, payload);
    }

    /** The body as sent: sorted keys, one space indent. */
    public byte[] body() {
        return Json.dumps(payload, Json.Style.indented(1)).getBytes(StandardCharsets.UTF_8);
    }

    /** Whether the status is a success. */
    public boolean ok() {
        return status >= 200 && status < 300;
    }
}
