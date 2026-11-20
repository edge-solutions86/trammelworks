package io.github.rkaushikdev.trammelworks.archive;

import java.nio.file.Path;

/**
 * What an opened archive says about itself.
 *
 * @param path the file
 * @param version the schema version it was written with
 * @param records how many records it holds
 */
public record ArchiveInfo(Path path, int version, long records) {

    /** Whether it holds nothing. */
    public boolean empty() {
        return records == 0;
    }
}
