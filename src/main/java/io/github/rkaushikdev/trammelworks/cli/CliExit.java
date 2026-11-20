package io.github.rkaushikdev.trammelworks.cli;

/** A command stopping with an exit code, after saying why. */
public final class CliExit extends RuntimeException {

    private final int code;

    /** Stop with {@code code}. */
    public CliExit(int code) {
        super("exit " + code, null, false, false);
        this.code = code;
    }

    /** The exit code. */
    public int code() {
        return code;
    }
}
