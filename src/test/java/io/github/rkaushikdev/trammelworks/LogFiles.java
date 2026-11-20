package io.github.rkaushikdev.trammelworks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builders for log lines in the shapes a site controller produces.
 *
 * <p>Written by hand rather than captured, because the interesting cases are the ones a real box
 * only produces under load: a line split at the chunk boundary, a rotation between two reads, a
 * truncated final line after a power cut.
 */
public final class LogFiles {

    /** Where the CRI runtime cuts a long line. */
    public static final int CRI_CHUNK = 16 * 1024;

    /** The pod uid every built path uses. */
    public static final String POD_UID = "1f2e3d4c-aaaa-bbbb-cccc-1234567890ab";

    private LogFiles() {}

    /** One physical CRI line, terminator included. */
    public static String criLine(String timestamp, String stream, String tag, String message) {
        return timestamp + " " + stream + " " + tag + " " + message + "\n";
    }

    /** One complete stdout CRI line. */
    public static String criFull(String timestamp, String message) {
        return criFull(timestamp, message, "stdout");
    }

    /** One complete CRI line on a given stream. */
    public static String criFull(String timestamp, String message, String stream) {
        return criLine(timestamp, stream, "F", message);
    }

    /** One application line as a partial chunk plus its final chunk. */
    public static String criSplit(String timestamp, String message, int at) {
        return criSplit(timestamp, message, at, "stdout");
    }

    /** One application line as a partial chunk plus its final chunk, on a given stream. */
    public static String criSplit(String timestamp, String message, int at, String stream) {
        return criLine(timestamp, stream, "P", message.substring(0, at))
                + criLine(timestamp, stream, "F", message.substring(at));
    }

    /** One json-file record. The runtime keeps the trailing newline in {@code log}. */
    public static String dockerLine(String timestamp, String message) {
        return dockerLine(timestamp, message, "stdout");
    }

    /** One json-file record on a given stream. */
    public static String dockerLine(String timestamp, String message, String stream) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("log", message + "\n");
        payload.put("stream", stream);
        payload.put("time", timestamp);
        return Json.dumps(payload) + "\n";
    }

    /** One journald export block, ended by a blank line. */
    public static String journalExport(Map<String, String> fields) {
        StringBuilder body = new StringBuilder();
        fields.forEach(
                (key, value) -> body.append(key).append('=').append(value).append('\n'));
        return body.append('\n').toString();
    }
}
