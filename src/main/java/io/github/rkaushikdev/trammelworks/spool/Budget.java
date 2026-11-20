package io.github.rkaushikdev.trammelworks.spool;

import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.SpoolError;

/**
 * What the spool may use, and the arithmetic on it.
 *
 * <p>The budget is a number the site is given, not a measurement of the filesystem: the controller
 * shares its disk with the payment kernel's database and filling the partition stops the tills. The
 * reserve is room kept back so an eviction always has somewhere to work, because eviction seals the
 * open segment before it can delete anything and sealing writes.
 *
 * @param limitBytes the whole budget
 * @param reserveRatio the part of it kept back, in {@code [0, 1)}
 */
public record Budget(long limitBytes, double reserveRatio) {

    /** The default reserve. */
    public static final double DEFAULT_RESERVE_RATIO = 0.1;

    /** Refuses a non-positive limit or a reserve outside {@code [0, 1)}. */
    public Budget {
        if (limitBytes <= 0) {
            throw new SpoolError("spool budget must be positive", Long.toString(limitBytes));
        }
        if (!(reserveRatio >= 0.0 && reserveRatio < 1.0)) {
            throw new SpoolError("reserve ratio must be in [0, 1)", Repr.of(reserveRatio));
        }
    }

    /** A budget with the default reserve. */
    public Budget(long limitBytes) {
        this(limitBytes, DEFAULT_RESERVE_RATIO);
    }

    /** The bytes kept back. */
    public long reserveBytes() {
        return (long) (limitBytes * reserveRatio);
    }

    /** What may be filled before eviction has to run. */
    public long usableBytes() {
        return limitBytes - reserveBytes();
    }

    /** Bytes still available inside the usable part, never negative. */
    public long free(Usage usage) {
        return Math.max(0, usableBytes() - usage.used());
    }

    /** True when a write of this size fits without eviction. */
    public boolean admits(Usage usage, long size) {
        if (size < 0) {
            throw new SpoolError("cannot admit a negative size", Long.toString(size));
        }
        return usage.used() + size <= usableBytes();
    }

    /** How much has to be freed to get back inside the usable part. */
    public long overBy(Usage usage) {
        return Math.max(0, usage.used() - usableBytes());
    }

    /** True when even the reserve would not cover this write. */
    public boolean wouldExceedHardLimit(Usage usage, long size) {
        return usage.used() + size > limitBytes;
    }

    /** How full the spool is, against the whole budget rather than the usable part. */
    public double fillRatio(Usage usage) {
        return usage.used() / (double) limitBytes;
    }
}
