package io.github.rkaushikdev.trammelworks.spool;

import io.github.rkaushikdev.trammelworks.JsonFiles;
import io.github.rkaushikdev.trammelworks.SpoolError;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reading segments back, and picking up after a power cut.
 *
 * <p>A {@code .part} file is a segment that was being written when the process stopped. That is not
 * damage: it is the normal state of the file the box was writing when the forecourt lost power, and
 * it holds records nothing else has. Recovery walks the frames, truncates where the good ones stop
 * and seals the rest. A sealed segment that fails to read is a different thing, media rot or a bug,
 * so it is quarantined rather than truncated: the evidence of a disk going bad is kept.
 */
public final class SegmentReader {

    /** The suffix a quarantined segment gets. */
    public static final String QUARANTINE_SUFFIX = ".bad";

    private SegmentReader() {}

    /** Read every whole frame in a segment file. */
    public static SegmentRead readSegment(Path path) {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException exc) {
            throw new SpoolError("segment unreadable", JsonFiles.describe(exc));
        }
        Frames.Run run = Frames.iterFrames(data);
        return new SegmentRead(path, run.frames(), run.consumed(), data.length, run.note());
    }

    /** Truncate an interrupted segment to its last good frame and seal it. */
    public static Recovery recoverPartial(Path path) {
        String name = path.getFileName().toString();
        if (!name.endsWith(Segments.PARTIAL_SUFFIX)) {
            throw new SpoolError("not a partial segment", name);
        }
        String sealedName = name.substring(0, name.length() - Segments.PARTIAL_SUFFIX.length());
        Segments.Name parsed = Segments.parseSegmentName(sealedName)
                .orElseThrow(() -> new SpoolError("partial segment has an unreadable name", name));
        SegmentRead found = readSegment(path);
        long dropped = (long) found.size() - found.consumed();
        try {
            if (found.consumed() == 0) {
                Files.delete(path);
                return new Recovery(path, Optional.empty(), 0, dropped, true);
            }
            if (dropped != 0) {
                try (FileChannel handle = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    handle.truncate(found.consumed());
                    handle.force(true);
                }
            }
            Path target = path.resolveSibling(sealedName);
            JsonFiles.replace(path, target);
            SegmentInfo sealed = new SegmentInfo(target, parsed.sequence(), parsed.klass(), found.consumed());
            return new Recovery(path, Optional.of(sealed), found.frames().size(), dropped, false);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    /** Deal with every interrupted segment, oldest first. */
    public static List<Recovery> recoverDirectory(Path directory) {
        if (!Files.exists(directory)) {
            return List.of();
        }
        List<Recovery> out = new ArrayList<>();
        for (Path path : Segments.sortedListing(directory)) {
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(Segments.PARTIAL_SUFFIX)) {
                out.add(recoverPartial(path));
            }
        }
        return out;
    }

    /** Move a sealed segment that will not read out of the way, replacing any earlier one. */
    public static Path quarantine(Path path) {
        Path target = path.resolveSibling(path.getFileName() + QUARANTINE_SUFFIX);
        try {
            Files.deleteIfExists(target);
            JsonFiles.replace(path, target);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        return target;
    }
}
