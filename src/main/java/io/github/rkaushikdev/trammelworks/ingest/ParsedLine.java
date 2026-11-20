package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;

/** What both line oriented formats produce once a line is decoded. */
public interface ParsedLine {

    /** The timestamp the runtime wrote. */
    NanoTime timestamp();

    /** The descriptor the container wrote to. */
    Stream stream();

    /** The text, without the runtime's framing. */
    String message();

    /** Whether the line continues on the next one. */
    boolean partial();

    /** Whether bytes had to be replaced to decode it. */
    boolean lossy();
}
