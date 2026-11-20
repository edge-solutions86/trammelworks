package io.github.rkaushikdev.trammelworks.spool;

import io.github.rkaushikdev.trammelworks.SpoolError;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Segment files: what the spool is actually made of.
 *
 * <p>The spool is a directory of append only files. One per class is open for writing and the rest
 * are sealed; only sealed files are shipped or evicted, so nothing ever reads a file that is being
 * written to. The name carries what a reader needs before opening anything, {@code
 * 000000000041-payment.spool}: a zero padded sequence so a lexical sort is a numeric sort, and the
 * class, so an eviction sweep can decide from a directory listing alone. Sequence numbers never
 * restart, because reusing one would make an acknowledgement for the old file look like one for the
 * new.
 */
public final class Segments {

    /** A sealed segment's suffix. */
    public static final String SUFFIX = ".spool";

    /** The suffix a segment carries while it is being written. */
    public static final String PARTIAL_SUFFIX = ".part";

    /** How many digits a sequence is padded to. */
    public static final int SEQUENCE_DIGITS = 12;

    /** The default segment size. */
    public static final long DEFAULT_SEGMENT_BYTES = 8L * 1024 * 1024;

    static final Pattern NAME_PATTERN = Pattern.compile("^(?<sequence>\\d{12})-(?<klass>[a-z][a-z0-9_]*)\\.spool$");
    static final Pattern CLASS_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    private Segments() {}

    /** A segment name's two parts. */
    public record Name(long sequence, String klass) {}

    /** Refuse a malformed priority class name. */
    public static String checkClass(String name) {
        if (!CLASS_PATTERN.matcher(name).find()) {
            throw new SpoolError("malformed priority class", name);
        }
        return name;
    }

    /** The file name for a sealed segment. */
    public static String segmentName(long sequence, String klass) {
        if (sequence < 0) {
            throw new SpoolError("negative segment sequence", Long.toString(sequence));
        }
        return String.format("%0" + SEQUENCE_DIGITS + "d-%s%s", sequence, checkClass(klass), SUFFIX);
    }

    /** The sequence and class in a segment file name, if it is one. */
    public static Optional<Name> parseSegmentName(String name) {
        Matcher match = NAME_PATTERN.matcher(name);
        if (!match.find()) {
            return Optional.empty();
        }
        return Optional.of(new Name(Long.parseLong(match.group("sequence")), match.group("klass")));
    }

    static List<Path> sortedListing(Path directory) {
        try (Stream<Path> found = Files.list(directory)) {
            return found.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    /** Every sealed segment, oldest first. */
    public static List<SegmentInfo> listSegments(Path directory) {
        if (!Files.exists(directory)) {
            return List.of();
        }
        List<SegmentInfo> found = new ArrayList<>();
        for (Path path : sortedListing(directory)) {
            Optional<Name> parsed = parseSegmentName(path.getFileName().toString());
            if (parsed.isEmpty() || !Files.isRegularFile(path)) {
                continue;
            }
            try {
                found.add(new SegmentInfo(
                        path, parsed.get().sequence(), parsed.get().klass(), Files.size(path)));
            } catch (IOException exc) {
                throw new UncheckedIOException(exc);
            }
        }
        return found;
    }

    /** One past the highest sequence on disk, counting partials. */
    public static long nextSequence(Path directory) {
        if (!Files.exists(directory)) {
            return 0;
        }
        long highest = -1;
        for (Path path : sortedListing(directory)) {
            String name = path.getFileName().toString();
            if (name.endsWith(PARTIAL_SUFFIX)) {
                name = name.substring(0, name.length() - PARTIAL_SUFFIX.length());
            }
            Optional<Name> parsed = parseSegmentName(name);
            if (parsed.isPresent()) {
                highest = Math.max(highest, parsed.get().sequence());
            }
        }
        return highest + 1;
    }
}
