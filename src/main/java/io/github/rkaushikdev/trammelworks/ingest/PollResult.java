package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Fragment;
import io.github.rkaushikdev.trammelworks.ParseError;
import java.util.List;

/**
 * What one poll of a followed file produced.
 *
 * @param fragments the lines read
 * @param event what happened to the file
 * @param bytesRead bytes read this poll
 * @param skipped lines skipped
 * @param lostBytes bytes that were held back and are gone because the file was replaced
 * @param errors why the skipped lines were skipped
 */
public record PollResult(
        List<Fragment> fragments,
        TailEvent event,
        long bytesRead,
        int skipped,
        long lostBytes,
        List<ParseError> errors) {

    /** Copies the lists. */
    public PollResult {
        fragments = List.copyOf(fragments);
        errors = List.copyOf(errors);
    }

    /** Whether nothing at all happened. */
    public boolean empty() {
        return fragments.isEmpty() && event == TailEvent.NONE;
    }
}
