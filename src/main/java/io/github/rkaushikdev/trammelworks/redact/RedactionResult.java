package io.github.rkaushikdev.trammelworks.redact;

import java.util.List;

/**
 * One line, after the redaction pass.
 *
 * @param text the line as it may leave the box
 * @param hits every site rule hit
 * @param stripes stripe images, PIN blocks and card codes removed
 * @param pans card numbers masked
 */
public record RedactionResult(String text, List<RuleHit> hits, int stripes, int pans) {

    /** Copies the hits. */
    public RedactionResult {
        hits = List.copyOf(hits);
    }

    /** Whether anything at all was removed. */
    public boolean changed() {
        return !hits.isEmpty() || stripes != 0 || pans != 0;
    }
}
