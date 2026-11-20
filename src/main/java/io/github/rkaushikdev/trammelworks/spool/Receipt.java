package io.github.rkaushikdev.trammelworks.spool;

/**
 * Where a record landed in the spool.
 *
 * @param klass the class it was filed under
 * @param sequence the segment it went into
 * @param offset its offset in that segment
 * @param size its framed size
 */
public record Receipt(String klass, long sequence, long offset, int size) {}
