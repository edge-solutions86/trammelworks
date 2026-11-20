package io.github.rkaushikdev.trammelworks.cli;

import io.github.rkaushikdev.trammelworks.BatchVerdict;
import io.github.rkaushikdev.trammelworks.Replay;
import io.github.rkaushikdev.trammelworks.ReplayReport;
import io.github.rkaushikdev.trammelworks.SystemClock;
import io.github.rkaushikdev.trammelworks.ship.SigningKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** {@code stanchion verify}: check a directory of batch files against a site's signing key. */
@Command(name = "verify", description = "Check a directory of batch files against a site's signing key.")
final class VerifyCommand implements Callable<Integer> {

    static final int EXIT_REFUSED = 3;

    @ParentCommand
    Cli root;

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = "DIRECTORY", description = "The directory of batches.")
    Path directory;

    @Option(names = "--key", paramLabel = "PATH", description = "The signing key this site was issued.")
    Path keyPath;

    @Option(names = "--key-id", description = "The key id the signatures should carry.")
    String keyId;

    @Option(names = "--unsigned", description = "Accept batches that carry no signature.")
    boolean unsigned;

    @Option(names = "--quiet", description = "Print the refused ones only.")
    boolean quiet;

    @Override
    public Integer call() {
        CliContext context = root.context();
        if (!Files.isDirectory(directory)) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "Invalid value for 'DIRECTORY': Directory '" + directory + "' does not exist.");
        }
        if (keyPath != null && !Files.isRegularFile(keyPath)) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(), "Invalid value for '--key': File '" + keyPath + "' does not exist.");
        }
        if (keyPath != null && keyId == null) {
            throw context.fail("a key needs its key id, pass --key-id");
        }
        if (keyPath == null && !unsigned) {
            throw context.fail("pass --key with --key-id, or --unsigned to accept what is there");
        }
        SigningKey key = keyPath == null ? null : context.reporting(() -> SigningKey.read(keyPath, keyId));
        ReplayReport report =
                context.reporting(() -> Replay.replay(directory, key, new SystemClock().now(), !unsigned));
        if (report.verdicts().isEmpty()) {
            context.echo("no batch files in that directory");
            return Cli.EXIT_OK;
        }
        for (BatchVerdict verdict : report.verdicts()) {
            if (verdict.ok() && quiet) {
                continue;
            }
            context.echo(verdict);
        }
        context.echo(report);
        if (unsigned && key == null) {
            context.warn("nothing was verified, these batches carry no signature");
        }
        return report.refused().isEmpty() ? Cli.EXIT_OK : EXIT_REFUSED;
    }
}
