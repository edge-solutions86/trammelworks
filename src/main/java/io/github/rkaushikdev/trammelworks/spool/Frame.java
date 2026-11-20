package io.github.rkaushikdev.trammelworks.spool;

import java.util.Map;

/**
 * One framed record, decoded.
 *
 * @param payload the record as it was written
 * @param compressed whether the payload was deflated on disk
 * @param storedSize the whole frame's size on disk, header and checksum included
 */
public record Frame(Map<String, Object> payload, boolean compressed, int storedSize) {}
