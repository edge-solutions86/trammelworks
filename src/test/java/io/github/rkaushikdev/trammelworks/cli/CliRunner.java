package io.github.rkaushikdev.trammelworks.cli;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Runs the command line in process, capturing what it prints and the code it exits with. */
public final class CliRunner {

    private CliRunner() {}

    /**
     * What one invocation did.
     *
     * @param exitCode the exit code
     * @param output standard output and standard error, interleaved as written
     * @param stdout standard output alone
     * @param stderr standard error alone
     */
    public record Result(int exitCode, String output, String stdout, String stderr) {}

    /** Run with empty standard input. */
    public static Result invoke(String... args) {
        return invokeWithInput("", args);
    }

    /** Run with {@code input} on standard input. */
    public static Result invokeWithInput(String input, String... args) {
        StringBuilder mixed = new StringBuilder();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        try (PrintWriter outWriter = new PrintWriter(new Tee(mixed, out));
                PrintWriter errWriter = new PrintWriter(new Tee(mixed, err))) {
            int code = Cli.execute(
                    args, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), outWriter, errWriter);
            outWriter.flush();
            errWriter.flush();
            return new Result(code, mixed.toString(), out.toString(), err.toString());
        }
    }

    /** A writer appending to two buffers at once. */
    private static final class Tee extends Writer {
        private final StringBuilder both;
        private final StringBuilder own;

        Tee(StringBuilder both, StringBuilder own) {
            this.both = both;
            this.own = own;
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            both.append(buffer, offset, length);
            own.append(buffer, offset, length);
        }

        @Override
        public void flush() {
            // Both buffers are written through.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
