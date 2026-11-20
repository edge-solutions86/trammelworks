package io.github.rkaushikdev.trammelworks.archive;

/**
 * What the archive is taking up right now.
 *
 * @param mainBytes the database file
 * @param walBytes the write ahead log beside it
 * @param pageSize SQLite's page size
 * @param pageCount pages in the file
 * @param freePages pages on the free list
 */
public record Footprint(long mainBytes, long walBytes, long pageSize, long pageCount, long freePages) {

    /** Nothing at all. */
    public Footprint() {
        this(0, 0, 0, 0, 0);
    }

    /** A footprint with no free pages. */
    public Footprint(long mainBytes, long walBytes, long pageSize, long pageCount) {
        this(mainBytes, walBytes, pageSize, pageCount, 0);
    }

    /** What the card has given up, free pages and all. */
    public long total() {
        return mainBytes + walBytes;
    }

    /**
     * What the records actually occupy: live pages only. Not the file size, which deleting does not
     * move, and not the log, which deleting makes larger. This is the number a trim is measured
     * against, because it is the only one a trim can bring down.
     */
    public long used() {
        return Math.max(pageCount - freePages, 0) * pageSize;
    }

    /** What a vacuum would give back, near enough. */
    public long reclaimable() {
        return freePages * pageSize;
    }

    /** How much of the file is free pages. */
    public double freeFraction() {
        return mainBytes != 0 ? reclaimable() / (double) mainBytes : 0.0;
    }

    @Override
    public String toString() {
        return total() + " bytes, " + reclaimable() + " reclaimable (" + freePages + " of " + pageCount
                + " pages free)";
    }
}
