package io.github.rkaushikdev.trammelworks.spool;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What came out of one segment.
 *
 * @param path the file read
 * @param frames every whole frame, in order
 * @param consumed where the last whole frame ended
 * @param size the file's size
 * @param note why reading stopped early, when it did
 */
public record SegmentRead(Path path, List<Frame> frames, int consumed, int size, Optional<String> note) {

    /** Copies the frames. */
    public SegmentRead {
        frames = List.copyOf(frames);
    }

    /** Whether every byte was a whole, good frame. */
    public boolean whole() {
        return note.isEmpty() && consumed == size;
    }

    /** The records, in order. */
    public List<Map<String, Object>> payloads() {
        return frames.stream().map(Frame::payload).toList();
    }
}
