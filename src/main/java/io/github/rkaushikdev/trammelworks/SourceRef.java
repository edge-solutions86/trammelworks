package io.github.rkaushikdev.trammelworks;

/**
 * Where a fragment physically came from.
 *
 * <p>Kept on every fragment so that a support engineer holding a shipped record can go back to the
 * site image and point at the bytes.
 *
 * @param path the file the line was read from
 * @param offset the byte offset of the line's first byte
 * @param length the byte length of the line as stored, terminator included
 */
public record SourceRef(String path, long offset, long length) {

    /** Refuses a negative offset or length. */
    public SourceRef {
        if (offset < 0) {
            throw new IllegalArgumentException("negative offset " + offset);
        }
        if (length < 0) {
            throw new IllegalArgumentException("negative length " + length);
        }
    }

    /** The offset just past the line. */
    public long end() {
        return offset + length;
    }

    /** The same line seen from a file that starts {@code delta} bytes earlier. */
    public SourceRef shifted(long delta) {
        return new SourceRef(path, offset + delta, length);
    }

    @Override
    public String toString() {
        return path + "@" + offset + "+" + length;
    }
}
