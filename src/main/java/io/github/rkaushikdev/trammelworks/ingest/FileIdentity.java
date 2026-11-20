package io.github.rkaushikdev.trammelworks.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Enough of a stat result to tell one file from another: device plus inode.
 *
 * <p>That is weaker than it looks, because inodes are reused, which is why the tailer keeps a
 * signature of the file's opening bytes as well.
 *
 * @param device the device number
 * @param inode the inode number
 */
public record FileIdentity(long device, long inode) {

    /** The identity and size of a file, from one stat. */
    record Stat(FileIdentity identity, long size) {}

    /** The identity of the file at {@code path}, following links. */
    public static FileIdentity of(Path path) throws IOException {
        return stat(path).identity();
    }

    static Stat stat(Path path) throws IOException {
        try {
            Map<String, Object> found = Files.readAttributes(path, "unix:dev,ino,size");
            return new Stat(
                    new FileIdentity(((Number) found.get("dev")).longValue(), ((Number) found.get("ino")).longValue()),
                    ((Number) found.get("size")).longValue());
        } catch (UnsupportedOperationException exc) {
            var basic = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            Object key = basic.fileKey();
            return new Stat(new FileIdentity(0, key == null ? 0 : key.hashCode()), basic.size());
        }
    }
}
