package io.github.rkaushikdev.trammelworks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Writing a small JSON file so a power cut cannot leave half of one, and reading it back.
 *
 * <p>Four things keep state in a small JSON file: the read checkpoints, the clock ledger, the meter
 * and the acknowledgement watermarks. All four need the same three details right: the temporary
 * file goes in the same directory because a rename is only atomic within a filesystem, the bytes
 * are forced to disk before the rename, and the temporary file is removed if anything fails.
 *
 * <p>A file that will not parse is treated as absent, because there is nothing useful to do with a
 * corrupt checkpoint except start again.
 */
public final class JsonFiles {

    /** Suffix of the temporary file a write goes through. */
    public static final String TEMP_SUFFIX = ".tmp";

    private JsonFiles() {}

    /** Write a mapping as sorted JSON, atomically, creating the directory as needed. */
    public static Path writeAtomic(Path path, Map<String, ?> payload) {
        return writeAtomic(path, payload, null);
    }

    /** As {@link #writeAtomic(Path, Map)}, indented when {@code indent} is not {@code null}. */
    public static Path writeAtomic(Path path, Map<String, ?> payload, Integer indent) {
        Path directory = path.toAbsolutePath().getParent();
        Path temporary;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "tmp", TEMP_SUFFIX);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        try {
            byte[] body = Json.dumps(payload, Json.Style.sorted(indent)).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(body);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            replace(temporary, path);
        } catch (IOException exc) {
            deleteQuietly(temporary);
            throw new UncheckedIOException(exc);
        } catch (RuntimeException | Error exc) {
            deleteQuietly(temporary);
            throw exc;
        }
        return path;
    }

    /** Move a file over another, atomically where the filesystem can. */
    public static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exc) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException suppressed) {
            // The write already failed and that failure is the one worth reporting.
            return;
        }
    }

    /** Read a mapping, treating anything unusable as absent rather than fatal. */
    public static ReadResult readJson(Path path) {
        return readJson(path, null);
    }

    /** As {@link #readJson(Path)}, also treating a different {@code version} as corrupt. */
    public static ReadResult readJson(Path path, Integer version) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException exc) {
            return ReadResult.absent();
        } catch (IOException exc) {
            throw new StanchionError("state file unreadable", describe(exc), exc);
        }
        Object payload;
        try {
            payload = Json.loads(text);
        } catch (Json.JsonException exc) {
            return ReadResult.broken();
        }
        if (!(payload instanceof Map<?, ?>)) {
            return ReadResult.broken();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> mapping = (Map<String, Object>) payload;
        if (version != null && !sameNumber(mapping.get("version"), version)) {
            return ReadResult.broken();
        }
        return ReadResult.of(mapping);
    }

    /** Whether a decoded JSON value equals an integer: a whole float or a boolean counts too. */
    public static boolean sameNumber(Object value, long expected) {
        return switch (value) {
            case Long found -> found == expected;
            case Integer found -> found == expected;
            case Double found -> found == expected;
            case Boolean found -> (found ? 1 : 0) == expected;
            case null, default -> false;
        };
    }

    /** An I/O failure as an operator reads it: the reason and the file. */
    public static String describe(IOException exc) {
        String reason = exc.getMessage() == null ? exc.getClass().getSimpleName() : exc.getMessage();
        return exc.getClass().getSimpleName() + ": " + reason;
    }
}
