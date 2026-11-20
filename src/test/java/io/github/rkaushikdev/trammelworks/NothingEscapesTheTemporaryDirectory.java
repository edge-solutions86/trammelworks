package io.github.rkaushikdev.trammelworks;

import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Refuses a test class that wrote to where a real site would.
 *
 * <p>The config defaults are real absolute paths, because they are where this software lives on a
 * commissioned box, so a test that leaves a section at its default writes to the machine running the
 * suite. On Linux that is {@code /var/lib} and fails on permissions; elsewhere it can quietly
 * succeed. The check is that the paths do not exist after a class runs, not that the class created
 * them: comparing before against after stays silent once any earlier run has made them.
 *
 * <p>Registered for every test class through the extension service file.
 */
public final class NothingEscapesTheTemporaryDirectory implements AfterAllCallback {

    /** Where the defaults would put state. */
    public static final List<Path> DEFAULT_PATHS = List.of(
            Path.of(SpoolSettings.DEFAULT_DIRECTORY).getParent(),
            Path.of(ArchiveSettings.DEFAULT_PATH).getParent().getParent());

    @Override
    public void afterAll(ExtensionContext context) {
        List<Path> found = present(DEFAULT_PATHS);
        if (!found.isEmpty()) {
            throw new AssertionError(
                    "the suite wrote outside its temporary directory: "
                            + found.stream().map(Path::toString).collect(Collectors.joining(", "))
                            + ". Name every section of a config a test builds, because the defaults are where a real site lives.");
        }
    }

    /** Which of {@code paths} exist. */
    static List<Path> present(List<Path> paths) {
        return paths.stream().filter(Files::exists).toList();
    }
}
