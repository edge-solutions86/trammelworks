package io.github.rkaushikdev.trammelworks.spool;

import io.github.rkaushikdev.trammelworks.JsonFiles;
import io.github.rkaushikdev.trammelworks.SpoolError;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * The one segment of a class currently open for writing.
 *
 * <p>Writes go to a {@code .part} file and the seal is a rename, so a reader listing the directory
 * never sees a segment that is still growing and does not need a lock to be sure of that.
 */
public final class SegmentWriter {

    private final Path directory;
    private final long sequence;
    private final String klass;
    private final long maxBytes;
    private final int syncEvery;
    private long size;
    private int frames;
    private FileChannel handle;
    private int sinceSync;

    /** A writer with the default size and no forced syncs. */
    public SegmentWriter(Path directory, long sequence, String klass) {
        this(directory, sequence, klass, Segments.DEFAULT_SEGMENT_BYTES, 0);
    }

    /** A writer rolling at {@code maxBytes} and forcing to disk every {@code syncEvery} frames. */
    public SegmentWriter(Path directory, long sequence, String klass, long maxBytes, int syncEvery) {
        Segments.checkClass(klass);
        if (maxBytes <= 0) {
            throw new SpoolError("segment max_bytes must be positive", Long.toString(maxBytes));
        }
        if (syncEvery < 0) {
            throw new SpoolError("sync_every cannot be negative", Integer.toString(syncEvery));
        }
        this.directory = directory;
        this.sequence = sequence;
        this.klass = klass;
        this.maxBytes = maxBytes;
        this.syncEvery = syncEvery;
    }

    /** The sequence this writer fills. */
    public long sequence() {
        return sequence;
    }

    /** The class this writer holds. */
    public String klass() {
        return klass;
    }

    /** Bytes written so far. */
    public long size() {
        return size;
    }

    /** Frames written by this writer. */
    public int frames() {
        return frames;
    }

    /** The file being written. */
    public Path partialPath() {
        return directory.resolve(Segments.segmentName(sequence, klass) + Segments.PARTIAL_SUFFIX);
    }

    /** Where the file goes when it is sealed. */
    public Path sealedPath() {
        return directory.resolve(Segments.segmentName(sequence, klass));
    }

    /** Whether a handle is held. */
    public boolean isOpen() {
        return handle != null;
    }

    FileChannel stream() {
        if (handle == null) {
            try {
                Files.createDirectories(directory);
                handle = FileChannel.open(
                        partialPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                size = Files.size(partialPath());
            } catch (IOException exc) {
                throw new UncheckedIOException(exc);
            }
        }
        return handle;
    }

    /** Write one framed record. Returns the offset it landed at. */
    public long append(byte[] frame) {
        FileChannel channel = stream();
        long offset = size;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(frame);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        size += frame.length;
        frames++;
        sinceSync++;
        if (syncEvery != 0 && sinceSync >= syncEvery) {
            flush();
        }
        return offset;
    }

    /** Ask the filesystem to commit what has been written. */
    public void flush() {
        if (handle == null) {
            return;
        }
        try {
            handle.force(false);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        sinceSync = 0;
    }

    /** Whether the segment has reached its size. */
    public boolean full() {
        return size >= maxBytes;
    }

    /** True when this frame would take the segment past its size. */
    public boolean wouldOverflow(long frameSize) {
        return size + frameSize > maxBytes;
    }

    /**
     * Release the handle without sealing. The segment stays a {@code .part} file and the next start
     * recovers it, exactly as it would after a process that went away.
     */
    public void close() {
        if (handle == null) {
            return;
        }
        flush();
        try {
            handle.close();
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        } finally {
            handle = null;
        }
    }

    /** Close and rename. An empty segment is removed rather than sealed. */
    public Optional<SegmentInfo> seal() {
        close();
        Path partial = partialPath();
        try {
            if (!Files.exists(partial)) {
                return Optional.empty();
            }
            if (size == 0) {
                Files.delete(partial);
                return Optional.empty();
            }
            JsonFiles.replace(partial, sealedPath());
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        return Optional.of(new SegmentInfo(sealedPath(), sequence, klass, size));
    }
}
