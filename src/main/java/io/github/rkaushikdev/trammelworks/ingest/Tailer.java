package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Fragment;
import io.github.rkaushikdev.trammelworks.IngestError;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.PosixPath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Follows one log file that the runtime keeps moving out from under us.
 *
 * <p>It grows, the ordinary case. It is rotated by rename, and the identity changes. It is rotated
 * in place by {@code copytruncate}, and the identity stays while the size goes backwards. Size alone
 * is not enough either: a busy container can refill a truncated file past the old offset before the
 * next poll, so the opening bytes are kept as a signature, and a file that no longer starts with
 * them was rewritten. Bytes held back when a file is replaced are reported as lost, never hidden.
 */
public final class Tailer {

    /** How much is read per poll by default. */
    public static final int DEFAULT_CHUNK = 64 * 1024;

    /** How much of the head of the file is remembered. A CRI timestamp is well inside it. */
    public static final int SIGNATURE_BYTES = 64;

    private final Path path;
    private final FragmentReader reader;
    private final int chunkSize;
    private long offset;
    private FileIdentity identity;
    private byte[] pending = new byte[0];
    private byte[] signature = new byte[0];

    /** A tailer reading the default chunk per poll. */
    public Tailer(Path path, FragmentReader reader) {
        this(path, reader, DEFAULT_CHUNK);
    }

    /** A tailer reading at most {@code chunkSize} bytes per poll. */
    public Tailer(Path path, FragmentReader reader, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IngestError("chunk_size must be positive", Integer.toString(chunkSize));
        }
        this.path = path;
        this.reader = reader;
        this.chunkSize = chunkSize;
    }

    /** The followed file. */
    public Path path() {
        return path;
    }

    /** How far into the file has been read. */
    public long offset() {
        return offset;
    }

    /** The file's identity when it was last seen. */
    public Optional<FileIdentity> identity() {
        return Optional.ofNullable(identity);
    }

    /** The remembered opening bytes. */
    public byte[] signature() {
        return signature.clone();
    }

    /** Bytes held back because they do not form a complete line yet. */
    public int buffered() {
        return pending.length;
    }

    /** Read whatever is new, handling rotation and truncation. */
    public PollResult poll() {
        TailEvent event = TailEvent.NONE;
        long lost = 0;
        FileIdentity.Stat info;
        try {
            info = FileIdentity.stat(path);
        } catch (NoSuchFileException exc) {
            if (identity != null) {
                lost = pending.length;
                reset();
                return new PollResult(List.of(), TailEvent.MISSING, 0, 0, lost, List.of());
            }
            return new PollResult(List.of(), TailEvent.NONE, 0, 0, 0, List.of());
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        FileIdentity found = info.identity();
        byte[] head = signature.length > 0 ? head() : new byte[0];
        if (identity == null) {
            identity = found;
        } else if (!found.equals(identity)) {
            lost = restart(found);
            event = TailEvent.ROTATED;
        } else if (info.size() < offset) {
            lost = restart(found);
            event = TailEvent.TRUNCATED;
        } else if (signature.length > 0 && !startsWith(head, signature)) {
            // Same inode, same or larger size, different bytes at the front: copytruncate, and the
            // container refilled it before we looked.
            lost = restart(found);
            event = TailEvent.TRUNCATED;
        }
        if (info.size() <= offset) {
            return new PollResult(List.of(), event, 0, 0, lost, List.of());
        }
        byte[] chunk = readChunk();
        if (chunk.length == 0) {
            return new PollResult(List.of(), event, 0, 0, lost, List.of());
        }
        if (event == TailEvent.NONE) {
            event = TailEvent.GROWN;
        }
        byte[] buffer = Bytes.concat(pending, chunk);
        long base = offset - pending.length;
        ReadOutcome parsed = reader.read(buffer, PosixPath.asPosix(path), base);
        offset += chunk.length;
        pending = Bytes.slice(buffer, parsed.consumed(), buffer.length);
        rememberHead();
        List<Fragment> fragments = parsed.fragments();
        List<ParseError> errors = parsed.errors();
        return new PollResult(fragments, event, chunk.length, parsed.skipped(), lost, errors);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }

    private long restart(FileIdentity found) {
        long lost = pending.length;
        reset();
        identity = found;
        return lost;
    }

    private byte[] head() {
        try (InputStream handle = Files.newInputStream(path)) {
            return handle.readNBytes(SIGNATURE_BYTES);
        } catch (NoSuchFileException exc) {
            return new byte[0];
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    private void rememberHead() {
        if (signature.length < SIGNATURE_BYTES) {
            signature = head();
        }
    }

    private byte[] readChunk() {
        try (FileChannel handle = FileChannel.open(path, StandardOpenOption.READ)) {
            handle.position(offset);
            ByteBuffer buffer = ByteBuffer.allocate(chunkSize);
            while (buffer.hasRemaining()) {
                if (handle.read(buffer) < 0) {
                    break;
                }
            }
            return Arrays.copyOf(buffer.array(), buffer.position());
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    private void reset() {
        offset = 0;
        pending = new byte[0];
        identity = null;
        signature = new byte[0];
    }

    /** Resume from a checkpoint, with no identity known. */
    public void seekTo(long position) {
        seekTo(position, null);
    }

    /** Resume from a checkpoint. {@code known} may be {@code null}. */
    public void seekTo(long position, FileIdentity known) {
        if (position < 0) {
            throw new IngestError("cannot resume from a negative offset", Long.toString(position));
        }
        offset = position;
        pending = new byte[0];
        identity = known;
        signature = position != 0 ? head() : new byte[0];
    }
}
