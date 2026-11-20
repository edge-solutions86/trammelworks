package io.github.rkaushikdev.trammelworks.ingest;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Byte level helpers for the readers, which parse what the runtime wrote rather than what it
 * should have written. A line that is not valid UTF-8 is decoded with replacement characters and
 * reported as lossy, never dropped.
 */
public final class Bytes {

    private Bytes() {}

    /**
     * Text decoded from bytes, and whether anything had to be replaced.
     *
     * @param text the decoded text
     * @param lossy whether some bytes were not valid UTF-8
     */
    public record Decoded(String text, boolean lossy) {}

    /** Decode as UTF-8, replacing what is not valid and saying so. */
    public static Decoded decode(byte[] raw) {
        try {
            CharBuffer strict = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw));
            return new Decoded(strict.toString(), false);
        } catch (CharacterCodingException exc) {
            return new Decoded(new String(raw, StandardCharsets.UTF_8), true);
        }
    }

    /** Decode as UTF-8 with replacement, not caring whether anything was replaced. */
    public static String decodeReplacing(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8);
    }

    /** Decode as UTF-8, silently dropping anything that is not valid, such as a cut rune. */
    public static String decodeIgnoring(byte[] raw) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.IGNORE)
                    .onUnmappableCharacter(CodingErrorAction.IGNORE)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException exc) {
            throw new IllegalStateException("an ignoring decoder cannot fail", exc);
        }
    }

    /** Whether every byte is seven bit ASCII. */
    public static boolean isAscii(byte[] raw) {
        for (byte b : raw) {
            if (b < 0) {
                return false;
            }
        }
        return true;
    }

    /** Bytes as a string one character per byte, for running byte patterns. */
    public static String latin1(byte[] raw) {
        return new String(raw, StandardCharsets.ISO_8859_1);
    }

    /** The first index of {@code target} at or after {@code from}, or -1. */
    public static int indexOf(byte[] data, byte target, int from) {
        for (int index = Math.max(from, 0); index < data.length; index++) {
            if (data[index] == target) {
                return index;
            }
        }
        return -1;
    }

    /** The first index of {@code needle} at or after {@code from}, or -1. */
    public static int indexOf(byte[] data, byte[] needle, int from) {
        outer:
        for (int index = Math.max(from, 0); index <= data.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (data[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    /** Whether {@code data} contains {@code target}. */
    public static boolean contains(byte[] data, byte target) {
        return indexOf(data, target, 0) >= 0;
    }

    /** A copy of {@code data[from, to)}. */
    public static byte[] slice(byte[] data, int from, int to) {
        return Arrays.copyOfRange(data, from, to);
    }

    /** Two arrays end to end. */
    public static byte[] concat(byte[] left, byte[] right) {
        byte[] out = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    private static boolean whitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == 0x0b || b == 0x0c;
    }

    /** Leading and trailing ASCII whitespace removed. */
    public static byte[] strip(byte[] data) {
        int start = 0;
        int end = data.length;
        while (start < end && whitespace(data[start])) {
            start++;
        }
        while (end > start && whitespace(data[end - 1])) {
            end--;
        }
        return slice(data, start, end);
    }

    /** Whether the bytes are nothing but ASCII whitespace. */
    public static boolean isBlank(byte[] data) {
        for (byte b : data) {
            if (!whitespace(b)) {
                return false;
            }
        }
        return true;
    }

    /** Trailing carriage returns and newlines removed. */
    public static byte[] stripLineEnd(byte[] data) {
        int end = data.length;
        while (end > 0 && (data[end - 1] == '\n' || data[end - 1] == '\r')) {
            end--;
        }
        return slice(data, 0, end);
    }

    /** Split on a single byte at most {@code limit} times, keeping empty fields. */
    public static List<byte[]> split(byte[] data, byte separator, int limit) {
        List<byte[]> out = new ArrayList<>();
        int start = 0;
        while (out.size() < limit) {
            int found = indexOf(data, separator, start);
            if (found < 0) {
                break;
            }
            out.add(slice(data, start, found));
            start = found + 1;
        }
        out.add(slice(data, start, data.length));
        return out;
    }
}
