package io.github.rkaushikdev.trammelworks.ship;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Writes each batch into a directory, named by its batch id, for the engineer who visits a site
 * whose modem has been dead for a week. The signature goes beside it in its own file: the batch is
 * then the same bytes whether it went over the modem or onto a stick, and a directory that lost
 * its {@code .sig} files is obviously unsigned rather than quietly unverifiable.
 */
public final class FileTransport implements Transport {

    private final Path directory;
    private final Signer signer;
    private int written;

    /** An unsigned file transport. */
    public FileTransport(Path directory) {
        this(directory, null);
    }

    /** A file transport signing with {@code signer}, which may be {@code null}. */
    public FileTransport(Path directory, Signer signer) {
        this.directory = directory;
        this.signer = signer;
    }

    /** Where batches are written. */
    public Path directory() {
        return directory;
    }

    /** Batches written. */
    public int written() {
        return written;
    }

    @Override
    public Response send(Batch batch) {
        byte[] body = batch.encode();
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(batch.batchId() + ".json");
            Files.write(target, body);
            if (signer != null) {
                Path beside = target.resolveSibling(target.getFileName() + SIGNATURE_SUFFIX);
                Files.writeString(beside, signer.headerFor(batch, body) + "\n", StandardCharsets.UTF_8);
            }
        } catch (IOException exc) {
            return new Response(Outcome.RETRYABLE, 0, Objects.toString(exc.getMessage(), exc.toString()));
        }
        written++;
        return new Response(Outcome.OK, 200).withBytesSent(body.length);
    }
}
