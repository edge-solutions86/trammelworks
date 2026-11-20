package io.github.rkaushikdev.trammelworks.cli;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** The {@code stanchion} executable. */
public final class Main {

    private Main() {}

    /** Run the command line and exit with its code. */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Run the command line against the process's own streams and return the exit code. */
    public static int run(String[] args) {
        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
        return Cli.execute(args, System.in, out, err);
    }
}
