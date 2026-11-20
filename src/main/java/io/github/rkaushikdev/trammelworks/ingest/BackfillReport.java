package io.github.rkaushikdev.trammelworks.ingest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What one backfill pass found. */
public final class BackfillReport {

    private int files;
    private long records;
    private long skipped;
    private long bytesRead;
    private final List<String> failed = new ArrayList<>();

    /** Rotated files read. */
    public int files() {
        return files;
    }

    /** Records produced. */
    public long records() {
        return records;
    }

    /** Lines skipped. */
    public long skipped() {
        return skipped;
    }

    /** Bytes read, after decompression. */
    public long bytesRead() {
        return bytesRead;
    }

    /** The files that could not be read. */
    public List<String> failed() {
        return Collections.unmodifiableList(failed);
    }

    void file(long bytes) {
        files++;
        bytesRead += bytes;
    }

    void record() {
        records++;
    }

    void skip(long count) {
        skipped += count;
    }

    void fail(String path) {
        failed.add(path);
    }

    @Override
    public String toString() {
        String end = failed.isEmpty() ? "" : ", " + failed.size() + " unreadable";
        return "backfilled " + records + " records from " + files + " files" + end;
    }
}
