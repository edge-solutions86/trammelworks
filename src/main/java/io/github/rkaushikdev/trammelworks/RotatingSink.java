package io.github.rkaushikdev.trammelworks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * An event log sink that rolls itself over at a size.
 *
 * <p>On a box whose whole point is that nobody visits it, an unbounded log is a disk that fills in
 * eighteen months and takes the tills with it. One rotation, not many: the useful window for our
 * own diagnostics is "what happened before it went wrong last night".
 *
 * <p>It must not write under the container log root, see {@link #checkOutside}: that would be read
 * back in on the next poll, a feedback loop that looks, from a distance, exactly like a busy site.
 */
public final class RotatingSink implements EventSink, AutoCloseable {

    /** The default cap. */
    public static final long DEFAULT_MAX_BYTES = 8L * 1024 * 1024;

    /** The suffix the single previous file carries. */
    public static final String PREVIOUS_SUFFIX = ".1";

    private final Path path;
    private final long maxBytes;
    private long size;
    private int rotations;
    private Writer handle;

    /** A sink with the default cap. */
    public RotatingSink(Path path) {
        this(path, DEFAULT_MAX_BYTES);
    }

    /** A sink rolling over before a write would take it past {@code maxBytes}. */
    public RotatingSink(Path path, long maxBytes) {
        if (maxBytes < 1024) {
            throw new StanchionError("an event log cap below 1 KiB is not useful");
        }
        this.path = path;
        this.maxBytes = maxBytes;
    }

    /** The live file. */
    public Path path() {
        return path;
    }

    /** The single kept rotation. */
    public Path previousPath() {
        return path.resolveSibling(path.getFileName() + PREVIOUS_SUFFIX);
    }

    /** Bytes in the live file as this sink knows it. */
    public long size() {
        return size;
    }

    /** How many times this sink has rotated. */
    public int rotations() {
        return rotations;
    }

    private Writer stream() throws IOException {
        if (handle == null) {
            Path parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            handle = Files.newBufferedWriter(
                    path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            size = Files.size(path);
        }
        return handle;
    }

    /** Write one line, rotating first if it would not fit. */
    @Override
    public void write(String text) {
        long encoded = text.getBytes(StandardCharsets.UTF_8).length;
        if (size != 0 && size + encoded > maxBytes) {
            rotate();
        }
        try {
            stream().write(text);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        size += encoded;
    }

    @Override
    public void flush() {
        if (handle != null) {
            try {
                handle.flush();
            } catch (IOException exc) {
                throw new UncheckedIOException(exc);
            }
        }
    }

    /** Move the current file aside, replacing any previous one. */
    public Path rotate() {
        close();
        try {
            if (Files.exists(path)) {
                Files.move(path, previousPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        rotations++;
        size = 0;
        return previousPath();
    }

    @Override
    public void close() {
        if (handle != null) {
            try {
                handle.close();
            } catch (IOException exc) {
                throw new UncheckedIOException(exc);
            } finally {
                handle = null;
            }
        }
    }

    /** Everything this sink is holding, current file and previous. */
    public long totalBytes() {
        long found = 0;
        for (Path candidate : new Path[] {path, previousPath()}) {
            try {
                if (Files.exists(candidate)) {
                    found += Files.size(candidate);
                }
            } catch (IOException exc) {
                throw new UncheckedIOException(exc);
            }
        }
        return found;
    }

    /** Refuse an event log that the collector would read back in. */
    public static void checkOutside(Path path, String logRoot) {
        PosixPath root = PosixPath.of(logRoot);
        PosixPath target = PosixPath.of(path);
        if (root.equals(target.parent()) || target.parents().contains(root)) {
            throw new StanchionError("the event log must not live under the container log root", path.toString());
        }
    }
}
