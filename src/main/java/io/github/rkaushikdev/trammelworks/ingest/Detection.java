package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Layout;
import java.util.Map;
import java.util.Optional;

/**
 * What format a file was decided to be, and how much the path and the bytes agreed.
 *
 * @param format the decision
 * @param fromContent whether the bytes decided it, rather than the path
 * @param pathLayout what the path said, when it said anything
 */
public record Detection(Format format, boolean fromContent, Optional<Layout> pathLayout) {

    /** The format each path layout implies. */
    public static final Map<Layout, Format> EXPECTED_BY_LAYOUT =
            Map.of(Layout.CRI, Format.CRI, Layout.FLAT, Format.CRI, Layout.DOCKER, Format.JSONFILE);

    /**
     * True when the path implied a different format from the bytes: the one signal that a site was
     * upgraded in place and nobody wrote it down.
     */
    public boolean disagreed() {
        if (pathLayout.isEmpty() || !fromContent) {
            return false;
        }
        return EXPECTED_BY_LAYOUT.get(pathLayout.get()) != format;
    }
}
