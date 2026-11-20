package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.PosixPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** What the command line tests share: config files under the test's directory, and a quick look at files. */
final class CliFixtures {

    private CliFixtures() {}

    /** A path as a config file spells it. */
    static String posix(Path path) {
        return PosixPath.asPosix(path);
    }

    /** Write {@code body} as {@code site.yaml} under {@code tmp}. */
    static Path writeConfig(Path tmp, String body) {
        Path target = tmp.resolve("site.yaml");
        try {
            Files.writeString(target, body, StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        return target;
    }

    /** Files in {@code directory} whose names end with {@code suffix}, sorted. */
    static List<Path> files(Path directory, String suffix) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> found = Files.list(directory)) {
            return found.filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    /** Run against {@code config}. */
    static CliRunner.Result invoke(Path config, String... args) {
        String[] full = new String[args.length + 2];
        full[0] = "--config";
        full[1] = config.toString();
        System.arraycopy(args, 0, full, 2, args.length);
        return CliRunner.invoke(full);
    }
}
