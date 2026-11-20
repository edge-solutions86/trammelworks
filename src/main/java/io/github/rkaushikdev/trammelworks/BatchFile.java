package io.github.rkaushikdev.trammelworks;

import java.nio.file.Path;
import java.util.Optional;

/**
 * One batch on the stick, and the signature file beside it if there is one.
 *
 * @param path the batch body
 * @param signaturePath the signature beside it
 */
public record BatchFile(Path path, Optional<Path> signaturePath) {

    /** Whether a signature came with it. */
    public boolean signed() {
        return signaturePath.isPresent();
    }
}
