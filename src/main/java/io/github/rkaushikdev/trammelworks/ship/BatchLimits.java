package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.ShipError;

/**
 * What one request is allowed to carry. Bytes, because the meter is sold in bytes; records,
 * because forty thousand tank readings are small on the wire and still take the collector minutes
 * to ingest, which times the request out and produces the retry the byte bound was meant to avoid.
 *
 * @param maxBytes the most bytes of records per batch
 * @param maxRecords the most records per batch
 */
public record BatchLimits(long maxBytes, int maxRecords) {

    /** The default byte bound. */
    public static final long DEFAULT_MAX_BYTES = 256L * 1024;

    /** The default record bound. */
    public static final int DEFAULT_MAX_RECORDS = 2000;

    /** Refuses a non-positive bound. */
    public BatchLimits {
        if (maxBytes <= 0) {
            throw new ShipError("batch max_bytes must be positive", Long.toString(maxBytes));
        }
        if (maxRecords <= 0) {
            throw new ShipError("batch max_records must be positive", Integer.toString(maxRecords));
        }
    }

    /** The defaults. */
    public BatchLimits() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_RECORDS);
    }

    /** A byte bound with the default record bound. */
    public static BatchLimits bytes(long maxBytes) {
        return new BatchLimits(maxBytes, DEFAULT_MAX_RECORDS);
    }

    /** A record bound with the default byte bound. */
    public static BatchLimits records(int maxRecords) {
        return new BatchLimits(DEFAULT_MAX_BYTES, maxRecords);
    }
}
