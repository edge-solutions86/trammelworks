package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.Clock;
import io.github.rkaushikdev.trammelworks.SystemClock;

/** Signs the batches one site sends. */
public final class Signer {

    private final SigningKey key;
    private final Clock clock;
    private int signed;

    /** A signer on the system clock. */
    public Signer(SigningKey key) {
        this(key, new SystemClock());
    }

    /** A signer on a given clock. */
    public Signer(SigningKey key, Clock clock) {
        this.key = key;
        this.clock = clock;
    }

    /** The key. */
    public SigningKey key() {
        return key;
    }

    /** How many batches have been signed. */
    public int signed() {
        return signed;
    }

    /** Sign one batch's body now. */
    public Signature forBatch(Batch batch, byte[] body) {
        Signature found = Signing.sign(key, batch.site(), batch.batchId(), body, clock.now());
        signed++;
        return found;
    }

    /** The header line for one batch's body. */
    public String headerFor(Batch batch, byte[] body) {
        return forBatch(batch, body).header();
    }
}
