package io.github.rkaushikdev.trammelworks.archive;

import io.github.rkaushikdev.trammelworks.RetentionError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Keeping the archive inside the space the card actually has.
 *
 * <p>Retention answers "may this go"; it does not answer "is there room". Deleting rows in SQLite
 * puts pages on a free list rather than shrinking the file, so a vacuum gives back space that is
 * already free, and is only worth its write cycles once the free portion is large. Trimming takes
 * records retention would have kept, lowest class and oldest first, never a held one, and is
 * measured against the live pages rather than the file, because only that number moves when rows
 * go.
 */
public final class Space {

    /** The write ahead log's suffix. */
    public static final String WAL_SUFFIX = "-wal";

    /** The shared memory file's suffix. */
    public static final String SHM_SUFFIX = "-shm";

    /** Vacuum only once the free list is worth more than this. */
    public static final long DEFAULT_MIN_RECLAIM = 32L * 1024 * 1024;

    /** Or once it is this much of the file, whichever comes first. */
    public static final double DEFAULT_MIN_FRACTION = 0.2;

    /** Rows trimmed per batch. */
    public static final int DEFAULT_TRIM_BATCH = 5000;

    private Space() {}

    /** A file's size, or zero when it is not there. */
    public static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exc) {
            return 0;
        }
    }

    /** The write ahead log beside an archive. */
    public static Path walPath(Path path) {
        return path.resolveSibling(path.getFileName() + WAL_SUFFIX);
    }

    private static long pragma(ArchiveDb db, String name) {
        return ((Number) db.queryOne("PRAGMA " + name).orElseThrow().get(0)).longValue();
    }

    /** Read the footprint from the file and from the pragmas. */
    public static Footprint measure(ArchiveDb db, Path path) {
        long pageSize;
        long pageCount;
        long freePages;
        try {
            pageSize = pragma(db, "page_size");
            pageCount = pragma(db, "page_count");
            freePages = pragma(db, "freelist_count");
        } catch (SqlFailure exc) {
            throw new RetentionError("archive will not report its size", exc.getMessage());
        }
        return new Footprint(fileSize(path), fileSize(walPath(path)), pageSize, pageCount, freePages);
    }

    /** Whether the free space is worth the rewrite, with the default thresholds. */
    public static boolean worthVacuuming(Footprint footprint) {
        return worthVacuuming(footprint, DEFAULT_MIN_RECLAIM, DEFAULT_MIN_FRACTION);
    }

    /** Whether the free space is worth the rewrite it costs to get back. */
    public static boolean worthVacuuming(Footprint footprint, long minReclaim, double minFraction) {
        if (footprint.reclaimable() <= 0) {
            return false;
        }
        return footprint.reclaimable() >= minReclaim || footprint.freeFraction() >= minFraction;
    }

    /** Fold the write ahead log back into the database and truncate it. */
    public static void checkpoint(ArchiveDb db) {
        try {
            db.query("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SqlFailure exc) {
            throw new RetentionError("archive will not checkpoint", exc.getMessage());
        }
    }

    /**
     * Rewrite the archive without its free pages. Returns the bytes given back. There is a
     * checkpoint on both sides: the one before folds in pages the vacuum would otherwise not see,
     * and the one after matters more, because in WAL mode the rebuilt database lands in the log
     * first and without it the file is the size it was. SQLite builds the new file beside the old
     * one, so there has to be room for a copy, and the situation that produces a vacuum is a card
     * that is filling up.
     */
    public static long vacuum(ArchiveDb db, Path path) {
        long before;
        try {
            db.query("PRAGMA wal_checkpoint(TRUNCATE)");
            before = fileSize(path);
            if (!roomToVacuum(path, before)) {
                throw new RetentionError("not enough free space to vacuum the archive", before + " bytes needed");
            }
            db.execute("VACUUM");
            db.query("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SqlFailure exc) {
            throw new RetentionError("vacuum failed", exc.getMessage());
        }
        return Math.max(before - fileSize(path), 0);
    }

    /**
     * Whether the filesystem has room for the copy a vacuum makes. No answer is not a no: a site
     * that reports its disk oddly must still be able to vacuum, and SQLite fails safely anyway.
     */
    public static boolean roomToVacuum(Path path, long needed) {
        try {
            return Files.getFileStore(path.toAbsolutePath().getParent()).getUsableSpace() >= needed;
        } catch (IOException exc) {
            return true;
        }
    }
}
