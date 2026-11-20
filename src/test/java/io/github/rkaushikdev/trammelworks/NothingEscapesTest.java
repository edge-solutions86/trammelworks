package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The guard that keeps the suite off a real site's paths, checked with a planted path of each kind. */
class NothingEscapesTest {

    @TempDir
    Path tmp;

    @Test
    void theGuardWatchesWhereTheDefaultsLive() {
        assertThat(NothingEscapesTheTemporaryDirectory.DEFAULT_PATHS).containsOnly(Path.of("/var/lib/stanchion"));
    }

    @Test
    void aPathThatExistsIsReported() throws IOException {
        Path planted = Files.createDirectories(tmp.resolve("var").resolve("lib").resolve("stanchion"));
        assertThat(NothingEscapesTheTemporaryDirectory.present(List.of(planted)))
                .containsExactly(planted);
    }

    @Test
    void aPathThatDoesNotExistIsNot() {
        assertThat(NothingEscapesTheTemporaryDirectory.present(List.of(tmp.resolve("absent"))))
                .isEmpty();
    }
}
