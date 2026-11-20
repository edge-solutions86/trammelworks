package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Fragment;
import io.github.rkaushikdev.trammelworks.IngestError;
import io.github.rkaushikdev.trammelworks.JsonFiles;
import io.github.rkaushikdev.trammelworks.LogPathInfo;
import io.github.rkaushikdev.trammelworks.LogPaths;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.PosixPath;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Reads the rotated files nobody got to.
 *
 * <p>The tailer follows the live file. After a day of downtime the records that matter are in the
 * files logrotate moved aside, and nothing in the ordinary path will ever read them. Backfill is a
 * deliberate operation, never automatic: a day of rotated logs can put a gigabyte through the spool,
 * and on a metered link that is a decision somebody makes. Files are read oldest first, each with a
 * fresh joiner, because a rotation is a hard boundary.
 */
public final class Backfiller {

    /** The largest rotated file read, decompressed. */
    public static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

    private final String site;
    private final LogPathInfo info;
    private final JoinLimits limits;
    private final long maxBytes;
    private final BackfillReport report = new BackfillReport();

    /** A backfiller with the default limits. */
    public Backfiller(String site, LogPathInfo info) {
        this(site, info, new JoinLimits(), DEFAULT_MAX_BYTES);
    }

    /** A backfiller with given limits. */
    public Backfiller(String site, LogPathInfo info, JoinLimits limits, long maxBytes) {
        this.site = site;
        this.info = info;
        this.limits = limits;
        this.maxBytes = maxBytes;
    }

    /** What this pass has found so far. */
    public BackfillReport report() {
        return report;
    }

    /** Every rotated file beside a live one, oldest first. */
    public static List<LogPathInfo> rotatedSiblings(Path live) {
        Path parent = live.toAbsolutePath().getParent();
        String liveName = live.getFileName().toString();
        List<Path> listing;
        try (Stream<Path> found = Files.list(parent)) {
            listing = found.sorted(Discover.PATH_ORDER).toList();
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        List<LogPathInfo> out = new ArrayList<>();
        for (Path path : listing) {
            String name = path.getFileName().toString();
            if (!Files.isRegularFile(path) || name.equals(liveName) || !name.startsWith(liveName)) {
                continue;
            }
            try {
                LogPathInfo found = LogPaths.parseLogPath(PosixPath.asPosix(path));
                if (found.rotation() != 0 || found.compressed()) {
                    out.add(found);
                }
            } catch (ParseError exc) {
                // Not a rotation of this file; something else that happens to share the prefix.
                continue;
            }
        }
        out.sort(LogPaths.ROTATION_ORDER);
        return out;
    }

    /** Read a rotated file, decompressing where it needs it. */
    public static byte[] readWhole(Path path, boolean compressed, long maxBytes) {
        byte[] data;
        try {
            if (compressed) {
                if (Files.size(path) == 0) {
                    data = new byte[0];
                } else {
                    try (InputStream handle = new GZIPInputStream(Files.newInputStream(path))) {
                        data = handle.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
                    }
                }
            } else {
                data = Files.readAllBytes(path);
            }
        } catch (ZipException exc) {
            // A file that is simply not gzip must not report as an unreadable one, or the operator
            // goes looking at permissions.
            throw new IngestError("rotated file is not gzip", path.toString());
        } catch (EOFException exc) {
            throw new IngestError("rotated file is truncated", path.toString());
        } catch (IOException exc) {
            throw new IngestError("rotated file unreadable", JsonFiles.describe(exc));
        }
        if (data.length > maxBytes) {
            throw new IngestError("rotated file is larger than the cap", path.toString());
        }
        return data;
    }

    /** The rotated files this backfill reads. */
    public List<LogPathInfo> sources() {
        return rotatedSiblings(Path.of(info.path()));
    }

    /** Every record in the rotated files, oldest first. */
    public List<LogRecord> records() {
        List<LogRecord> out = new ArrayList<>();
        forEachRecord(out::add);
        return out;
    }

    /** Hand every record in the rotated files to {@code sink}, oldest first. */
    public void forEachRecord(Consumer<LogRecord> sink) {
        for (LogPathInfo source : sources()) {
            byte[] data;
            try {
                data = readWhole(Path.of(source.path()), source.compressed(), maxBytes);
            } catch (IngestError exc) {
                report.fail(source.path());
                continue;
            }
            report.file(data.length);
            recordsIn(source, data, sink);
        }
    }

    private void recordsIn(LogPathInfo source, byte[] data, Consumer<LogRecord> sink) {
        Detection found;
        try {
            found = Detect.detect(source.path(), Arrays.copyOf(data, Math.min(data.length, Detect.SAMPLE_BYTES)));
        } catch (ParseError exc) {
            report.fail(source.path());
            return;
        }
        Joiner joiner = new Joiner(site, source.unit(), limits);
        ReadOutcome parsed = FragmentReader.forFormat(found.format()).read(data, source.path());
        report.skip(parsed.skipped());
        for (Fragment fragment : parsed.fragments()) {
            joiner.feed(fragment).ifPresent(record -> {
                report.record();
                sink.accept(record);
            });
        }
        for (LogRecord record : joiner.flush()) {
            report.record();
            sink.accept(record);
        }
    }
}
