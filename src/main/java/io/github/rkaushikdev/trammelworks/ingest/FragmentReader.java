package io.github.rkaushikdev.trammelworks.ingest;

/**
 * Reads a buffer into fragments, consuming only complete units.
 *
 * <p>Consuming only what is complete is what makes resume safe: if a reader consumed a half
 * written line, the checkpoint would advance past bytes that had not been parsed. A unit that will
 * not parse is skipped rather than fatal, because refusing the file loses the rest of the shift.
 */
public interface FragmentReader {

    /** Read {@code data}, whose first byte sits at {@code baseOffset} in the file at {@code path}. */
    ReadOutcome read(byte[] data, String path, long baseOffset);

    /** Read a buffer that starts at the beginning of the file. */
    default ReadOutcome read(byte[] data, String path) {
        return read(data, path, 0);
    }

    /** The reader that goes with a detected format. */
    static FragmentReader forFormat(Format format) {
        return switch (format) {
            case CRI -> LineReader.cri();
            case JSONFILE -> LineReader.jsonFile();
            case JOURNAL -> new JournalReader();
        };
    }
}
