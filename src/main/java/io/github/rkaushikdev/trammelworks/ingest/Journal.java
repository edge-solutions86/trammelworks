package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Bytes;
import io.github.rkaushikdev.trammelworks.ParseError;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The journald export format.
 *
 * <p>An entry is a run of {@code NAME=value} lines ended by a blank line. A value containing a
 * newline cannot be written that way, so the exporter switches to a binary form: the name alone on
 * a line, eight bytes of little endian length, the raw value, a newline. A parser that only handles
 * the text form silently loses exactly the entries worth having, because a multi line value is
 * usually a stack trace.
 */
public final class Journal {

    static final int LENGTH_PREFIX = 8;

    private Journal() {}

    /** Parse a whole export stream into entries. */
    public static List<JournalEntry> parseExport(byte[] data) {
        List<JournalEntry> entries = new ArrayList<>();
        Map<String, String> fields = new LinkedHashMap<>();
        Set<String> binary = new HashSet<>();
        int position = 0;
        while (position < data.length) {
            int end = Bytes.indexOf(data, (byte) '\n', position);
            byte[] line = end < 0 ? Bytes.slice(data, position, data.length) : Bytes.slice(data, position, end);
            int next = end < 0 ? data.length : end + 1;
            if (line.length == 0) {
                if (!fields.isEmpty()) {
                    entries.add(new JournalEntry(fields, binary));
                    fields = new LinkedHashMap<>();
                    binary = new HashSet<>();
                }
                position = next;
                continue;
            }
            int equals = Bytes.indexOf(line, (byte) '=', 0);
            if (equals >= 0) {
                String name = Bytes.decodeReplacing(Bytes.slice(line, 0, equals));
                fields.put(name, Bytes.decodeReplacing(Bytes.slice(line, equals + 1, line.length)));
                position = next;
                continue;
            }
            String name = Bytes.decodeReplacing(line);
            int header = next;
            if ((long) header + LENGTH_PREFIX > data.length) {
                throw new ParseError("journal binary field is truncated", name);
            }
            long size = ByteBuffer.wrap(data, header, LENGTH_PREFIX)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getLong();
            long bodyStart = (long) header + LENGTH_PREFIX;
            long bodyEnd = bodyStart + size;
            if (size < 0 || bodyEnd > data.length) {
                throw new ParseError("journal binary value runs past the end", name);
            }
            fields.put(name, Bytes.decodeReplacing(Bytes.slice(data, (int) bodyStart, (int) bodyEnd)));
            binary.add(name);
            position = bodyEnd < data.length && data[(int) bodyEnd] == '\n' ? (int) bodyEnd + 1 : (int) bodyEnd;
        }
        if (!fields.isEmpty()) {
            entries.add(new JournalEntry(fields, binary));
        }
        return entries;
    }

    /** Render an entry back out, using the binary form where it is needed. */
    public static byte[] render(JournalEntry entry) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : entry.fields().entrySet()) {
            byte[] name = field.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] encoded = field.getValue().getBytes(StandardCharsets.UTF_8);
            out.writeBytes(name);
            if (Bytes.contains(encoded, (byte) '\n') || entry.binary().contains(field.getKey())) {
                out.write('\n');
                out.writeBytes(ByteBuffer.allocate(LENGTH_PREFIX)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(encoded.length)
                        .array());
                out.writeBytes(encoded);
                out.write('\n');
            } else {
                out.write('=');
                out.writeBytes(encoded);
                out.write('\n');
            }
        }
        out.write('\n');
        return out.toByteArray();
    }
}
