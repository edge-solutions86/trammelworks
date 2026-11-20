package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Fragment;
import io.github.rkaushikdev.trammelworks.ParseError;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What one reader pass over a buffer produced, and how much of it was consumed. */
public final class ReadOutcome {

    private final List<Fragment> fragments = new ArrayList<>();
    private final List<ParseError> errors = new ArrayList<>();
    private int consumed;
    private int skipped;

    /** The fragments, in order. */
    public List<Fragment> fragments() {
        return Collections.unmodifiableList(fragments);
    }

    /** Bytes consumed: only whole units, so the remainder can be handed back with more. */
    public int consumed() {
        return consumed;
    }

    /** Units skipped, blank or unparseable. */
    public int skipped() {
        return skipped;
    }

    /** Why the unparseable ones were skipped. */
    public List<ParseError> errors() {
        return Collections.unmodifiableList(errors);
    }

    void add(Fragment fragment) {
        fragments.add(fragment);
    }

    void consumedTo(int position) {
        consumed = position;
    }

    void skip() {
        skipped++;
    }

    void skip(ParseError error) {
        skipped++;
        errors.add(error);
    }
}
