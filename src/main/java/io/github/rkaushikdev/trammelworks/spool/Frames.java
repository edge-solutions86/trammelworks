package io.github.rkaushikdev.trammelworks.spool;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.SpoolError;
import io.github.rkaushikdev.trammelworks.Version;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * How one record sits on disk.
 *
 * <pre>
 *   magic   2 bytes   "SP"
 *   version 1 byte    the spool format
 *   flags   1 byte    bit 0 set when the payload is deflated
 *   length  4 bytes   little endian, payload length
 *   payload N bytes   the record as compact JSON
 *   crc     4 bytes   little endian crc32 of the payload
 * </pre>
 *
 * <p>The checksum covers the payload only: a torn write leaves a header whose length says how far
 * to look, and covering the header too would mean not trusting that length enough to find the
 * checksum. A partial frame at the end of a segment is what a power cut leaves, so it is detected
 * and dropped and the frames before it stay good. Compression is per frame, so a segment can still
 * be appended to and read past a corrupt point.
 */
public final class Frames {

    /** The two bytes every frame starts with. */
    public static final byte[] MAGIC = {'S', 'P'};

    /** Magic, version, flags and length. */
    public static final int HEADER_SIZE = 8;

    /** The trailing checksum. */
    public static final int CRC_SIZE = 4;

    /** Everything a frame adds to its payload. */
    public static final int OVERHEAD = HEADER_SIZE + CRC_SIZE;

    /** Set when the payload is deflated. */
    public static final int FLAG_DEFLATE = 0x01;

    /** Below this a deflate stream is bigger than what went in, so it is not tried. */
    public static final int MIN_COMPRESS_BYTES = 200;

    /** The largest payload a frame may claim. */
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private static final int DEFLATE_LEVEL = 6;

    private Frames() {}

    /** A decoded frame and where the next one starts. */
    public record Decoded(Frame frame, int next) {}

    /**
     * Every whole frame in a buffer, how many bytes they took, and why the walk stopped.
     *
     * @param frames the frames, in order
     * @param consumed where the last whole frame ended, which is where a truncate should land
     * @param note why the walk stopped early, when it did
     */
    public record Run(List<Frame> frames, int consumed, Optional<String> note) {

        /** Copies the frames. */
        public Run {
            frames = List.copyOf(frames);
        }
    }

    /** Encode one record, deflating it when that helps. */
    public static byte[] encodeFrame(Map<String, ?> payload) {
        return encodeFrame(payload, true);
    }

    /** Encode one record into its on disk form. */
    public static byte[] encodeFrame(Map<String, ?> payload, boolean compress) {
        byte[] body;
        try {
            body = Json.dumps(payload, Json.Style.compact()).getBytes(StandardCharsets.UTF_8);
        } catch (Json.JsonException exc) {
            throw new SpoolError("record cannot be encoded", exc.getMessage());
        }
        int flags = 0;
        if (compress && body.length >= MIN_COMPRESS_BYTES) {
            byte[] squeezed = deflate(body);
            if (squeezed.length < body.length) {
                body = squeezed;
                flags |= FLAG_DEFLATE;
            }
        }
        if (body.length > MAX_PAYLOAD_BYTES) {
            throw new SpoolError("record is too large to spool", Integer.toString(body.length));
        }
        ByteBuffer out = ByteBuffer.allocate(OVERHEAD + body.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC).put((byte) Version.SPOOL_FORMAT).put((byte) flags).putInt(body.length);
        out.put(body).putInt((int) crc32(body, 0, body.length));
        return out.array();
    }

    static byte[] deflate(byte[] body) {
        Deflater deflater = new Deflater(DEFLATE_LEVEL);
        try {
            deflater.setInput(body);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(body.length);
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                out.write(chunk, 0, deflater.deflate(chunk));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    static byte[] inflate(byte[] body) throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(body);
            ByteArrayOutputStream out = new ByteArrayOutputStream(body.length * 4);
            byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(chunk);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new DataFormatException("incomplete or truncated stream");
                }
                out.write(chunk, 0, count);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    static long crc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return crc.getValue();
    }

    private static long littleEndian(byte[] data, int offset) {
        return (data[offset] & 0xffL)
                | (data[offset + 1] & 0xffL) << 8
                | (data[offset + 2] & 0xffL) << 16
                | (data[offset + 3] & 0xffL) << 24;
    }

    /** Total length of the frame starting at {@code offset}, or empty if it is not all there. */
    public static OptionalInt frameLength(byte[] data, int offset) {
        if ((long) offset + HEADER_SIZE > data.length) {
            return OptionalInt.empty();
        }
        if (data[offset] != MAGIC[0] || data[offset + 1] != MAGIC[1]) {
            throw new SpoolError("frame does not start with the magic", Integer.toString(offset));
        }
        long size = littleEndian(data, offset + 4);
        if (size > MAX_PAYLOAD_BYTES) {
            throw new SpoolError("frame claims an impossible length", Long.toString(size));
        }
        int total = OVERHEAD + (int) size;
        if ((long) offset + total > data.length) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(total);
    }

    /** As {@link #frameLength(byte[], int)} from the start of the buffer. */
    public static OptionalInt frameLength(byte[] data) {
        return frameLength(data, 0);
    }

    /** Decode the frame at an offset. */
    public static Decoded decodeFrame(byte[] data, int offset) {
        OptionalInt length = frameLength(data, offset);
        if (length.isEmpty()) {
            throw new SpoolError("frame is truncated", Integer.toString(offset));
        }
        int total = length.getAsInt();
        int version = data[offset + 2] & 0xff;
        if (version != Version.SPOOL_FORMAT) {
            throw new SpoolError("frame was written by another spool format", Integer.toString(version));
        }
        int flags = data[offset + 3] & 0xff;
        int size = total - OVERHEAD;
        int bodyStart = offset + HEADER_SIZE;
        long stored = littleEndian(data, bodyStart + size);
        if (crc32(data, bodyStart, size) != stored) {
            throw new SpoolError("frame checksum does not match", Integer.toString(offset));
        }
        byte[] body = Arrays.copyOfRange(data, bodyStart, bodyStart + size);
        boolean compressed = (flags & FLAG_DEFLATE) != 0;
        if (compressed) {
            try {
                body = inflate(body);
            } catch (DataFormatException exc) {
                throw new SpoolError("frame will not decompress", Integer.toString(offset));
            }
        }
        Object payload;
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
            payload = Json.loads(text);
        } catch (CharacterCodingException | Json.JsonException exc) {
            throw new SpoolError("frame payload is not json", Integer.toString(offset));
        }
        if (!(payload instanceof Map<?, ?>)) {
            throw new SpoolError("frame payload is not an object", Integer.toString(offset));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> mapping = (Map<String, Object>) payload;
        return new Decoded(new Frame(mapping, compressed, total), offset + total);
    }

    /** As {@link #decodeFrame(byte[], int)} from the start of the buffer. */
    public static Decoded decodeFrame(byte[] data) {
        return decodeFrame(data, 0);
    }

    /**
     * Decode every whole frame in a buffer. A tail that is not a whole frame is not an error: it is
     * what a power cut looks like, and the caller truncates the segment to the consumed length.
     */
    public static Run iterFrames(byte[] data) {
        List<Frame> frames = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            Decoded decoded;
            try {
                decoded = decodeFrame(data, offset);
            } catch (SpoolError exc) {
                return new Run(frames, offset, Optional.of(exc.message()));
            }
            frames.add(decoded.frame());
            offset = decoded.next();
        }
        return new Run(frames, offset, Optional.empty());
    }
}
