package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Fragment;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.SourceRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Reads the journald export format, whose unit is a block ended by a blank line, not a line.
 *
 * <p>This is the one format that knows where in the boot session its entry sits, so {@code
 * _BOOT_ID} and {@code __MONOTONIC_TIMESTAMP} are carried through as labels for clock correction
 * to use instead of estimating a position from the wall clock it is trying to correct.
 */
public final class JournalReader implements FragmentReader {

    /** The label carrying nanoseconds since boot. */
    public static final String MONOTONIC_LABEL = "monotonic_ns";

    /** The label carrying the boot session. */
    public static final String BOOT_LABEL = "boot_id";

    private static final byte[] BLOCK_TERMINATOR = {'\n', '\n'};

    @Override
    public ReadOutcome read(byte[] data, String path, long baseOffset) {
        ReadOutcome result = new ReadOutcome();
        int start = 0;
        while (true) {
            int end = Bytes.indexOf(data, BLOCK_TERMINATOR, start);
            if (end < 0) {
                break;
            }
            byte[] block = Bytes.slice(data, start, end + BLOCK_TERMINATOR.length);
            int offset = start;
            start = end + BLOCK_TERMINATOR.length;
            result.consumedTo(start);
            List<JournalEntry> entries;
            try {
                entries = Journal.parseExport(block);
            } catch (ParseError exc) {
                result.skip(exc);
                continue;
            }
            for (JournalEntry entry : entries) {
                try {
                    result.add(fragment(entry, path, baseOffset + offset, block.length));
                } catch (ParseError exc) {
                    result.skip(exc);
                }
            }
        }
        return result;
    }

    private static Fragment fragment(JournalEntry entry, String path, long offset, int length) {
        Map<String, String> labels = new LinkedHashMap<>();
        OptionalLong monotonic = entry.monotonicUs();
        if (monotonic.isPresent()) {
            labels.put(MONOTONIC_LABEL, Long.toString(monotonic.getAsLong() * NanoTime.NS_PER_US));
        }
        entry.bootId().ifPresent(boot -> labels.put(BOOT_LABEL, boot));
        return new Fragment(
                entry.realtime(),
                entry.stream(),
                entry.message(),
                new SourceRef(path, offset, length),
                false,
                false,
                labels);
    }
}
