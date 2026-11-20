package io.github.rkaushikdev.trammelworks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Random;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/**
 * Temporary directories named with letters only.
 *
 * <p>Paths end up inside records (a record's sources name the file it came from), and the tests
 * that prove no card number reaches the wire or the spool scan whole payloads for one. A directory
 * name carrying a long run of random digits passes the card check about one run in twenty, which
 * reads as a leak that is not there. Letters cannot.
 */
public final class LetterTempDirFactory implements TempDirFactory {

    private static final Random RANDOM = new SecureRandom();

    @Override
    public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext extension) throws IOException {
        Path base = Path.of(System.getProperty("java.io.tmpdir"));
        Path candidate = base.resolve("stanchion-test-" + letters(16));
        while (Files.exists(candidate)) {
            candidate = base.resolve("stanchion-test-" + letters(16));
        }
        return Files.createDirectory(candidate);
    }

    /** {@code count} random lower case letters. */
    static String letters(int count) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < count; index++) {
            out.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return out.toString();
    }
}
