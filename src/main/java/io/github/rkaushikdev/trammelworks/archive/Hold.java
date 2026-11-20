package io.github.rkaushikdev.trammelworks.archive;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.RetentionError;
import io.github.rkaushikdev.trammelworks.Text;
import java.util.Optional;

/**
 * A named reason not to delete something: placed by the estate when a card scheme or a court asks,
 * and nothing automatic may work around one.
 *
 * @param name the hold's name
 * @param reason why it was placed
 * @param placedAt when
 * @param coversFrom the start of the window it keeps, or open
 * @param coversUntil the end of the window it keeps, or open
 */
public record Hold(
        String name, String reason, NanoTime placedAt, Optional<NanoTime> coversFrom, Optional<NanoTime> coversUntil) {

    /** Refuses a nameless hold or one that ends before it starts. */
    public Hold {
        if (Text.strip(name).isEmpty()) {
            throw new RetentionError("a hold needs a name");
        }
        if (coversFrom.isPresent()
                && coversUntil.isPresent()
                && coversUntil.get().compareTo(coversFrom.get()) < 0) {
            throw new RetentionError("a hold cannot end before it starts", name);
        }
    }

    /** A hold over everything. */
    public Hold(String name, String reason, NanoTime placedAt) {
        this(name, reason, placedAt, Optional.empty(), Optional.empty());
    }

    /** Whether the hold keeps everything, past and future. */
    public boolean openEnded() {
        return coversFrom.isEmpty() && coversUntil.isEmpty();
    }

    /** True when this instant is inside the hold. */
    public boolean covers(NanoTime when) {
        if (coversFrom.isPresent() && when.compareTo(coversFrom.get()) < 0) {
            return false;
        }
        return coversUntil.isEmpty() || when.compareTo(coversUntil.get()) <= 0;
    }
}
