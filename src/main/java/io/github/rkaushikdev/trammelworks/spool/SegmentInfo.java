package io.github.rkaushikdev.trammelworks.spool;

import java.nio.file.Path;

/**
 * What a sealed segment's name and size tell us, without opening it.
 *
 * @param path where it is
 * @param sequence its sequence number, which never repeats on a site
 * @param klass the priority class it holds
 * @param size its size in bytes
 */
public record SegmentInfo(Path path, long sequence, String klass, long size) {

    /** The file name. */
    public String name() {
        return path.getFileName().toString();
    }
}
