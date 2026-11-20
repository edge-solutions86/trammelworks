package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Text;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Somewhere a batch can be sent: the HTTP collector, a directory an engineer copies onto a stick,
 * memory for tests and replay, or nowhere at all for measuring the rest of the pipeline.
 */
public interface Transport {

    /** The statuses worth another go. */
    Set<Integer> RETRYABLE_STATUS = Set.of(408, 425, 500, 502, 503, 504);

    /** The status that says slow down. */
    int THROTTLED_STATUS = 429;

    /** The header the signature travels in. */
    String SIGNATURE_HEADER = "X-Stanchion-Signature";

    /** The suffix of a signature written beside a batch file. */
    String SIGNATURE_SUFFIX = ".sig";

    /** Send one batch. */
    Response send(Batch batch);

    /**
     * Map an HTTP status onto what to do about it, once and here: a 408 or a 5xx is retryable, a
     * 429 is throttled, and any other 4xx is terminal because the same body will get the same
     * refusal and each attempt costs metered bytes.
     */
    static Outcome outcomeFor(int status) {
        if (status >= 200 && status < 300) {
            return Outcome.OK;
        }
        if (status == THROTTLED_STATUS) {
            return Outcome.THROTTLED;
        }
        if (RETRYABLE_STATUS.contains(status) || status >= 500) {
            return Outcome.RETRYABLE;
        }
        return Outcome.TERMINAL;
    }

    /** Read a Retry-After header, seconds form only. */
    static OptionalDouble parseRetryAfter(String raw) {
        if (raw == null) {
            return OptionalDouble.empty();
        }
        double seconds;
        try {
            seconds = Double.parseDouble(Text.strip(raw));
        } catch (NumberFormatException exc) {
            return OptionalDouble.empty();
        }
        return seconds >= 0 ? OptionalDouble.of(seconds) : OptionalDouble.empty();
    }

    /** Read a collector reply, tolerating one that is not JSON at all. */
    static Map<String, Object> decodeBody(byte[] raw) {
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw))
                    .toString();
            return Json.loadsObject(text);
        } catch (CharacterCodingException | Json.JsonException exc) {
            return Map.of();
        }
    }
}
