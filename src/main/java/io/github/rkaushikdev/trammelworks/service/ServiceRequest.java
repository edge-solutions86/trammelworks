package io.github.rkaushikdev.trammelworks.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What arrived.
 *
 * @param path the path, without a trailing slash
 * @param query every query parameter, each with the values given for it in order
 * @param headers the headers, names lower cased
 * @param method the method, upper cased
 */
public record ServiceRequest(String path, Map<String, List<String>> query, Map<String, String> headers, String method) {

    /** A GET for {@code target}, with no headers. */
    public static ServiceRequest of(String target) {
        return of(target, Map.of(), "GET");
    }

    /**
     * A request for {@code target}, a path with an optional query string and fragment. Parameters
     * with an empty value are dropped, {@code +} reads as a space and escapes are decoded.
     */
    public static ServiceRequest of(String target, Map<String, String> headers, String method) {
        String rest = target;
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }
        String rawQuery = "";
        int mark = rest.indexOf('?');
        if (mark >= 0) {
            rawQuery = rest.substring(mark + 1);
            rest = rest.substring(0, mark);
        }
        String path = rest;
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        Map<String, String> lowered = new LinkedHashMap<>();
        headers.forEach((key, value) -> lowered.put(key.toLowerCase(Locale.ROOT), value));
        return new ServiceRequest(
                path.isEmpty() ? "/" : path, parseQuery(rawQuery), lowered, method.toUpperCase(Locale.ROOT));
    }

    static Map<String, List<String>> parseQuery(String raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String pair : raw.split("&", -1)) {
            int equals = pair.indexOf('=');
            if (equals < 0 || equals == pair.length() - 1) {
                continue;
            }
            String name = unquotePlus(pair.substring(0, equals));
            String value = unquotePlus(pair.substring(equals + 1));
            out.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
        return out;
    }

    /**
     * A form-encoded piece decoded the forgiving way: {@code +} is a space, a well formed escape is
     * a byte, anything else stays as written, and bytes that are not UTF-8 become replacement
     * characters rather than an error.
     */
    static String unquotePlus(String raw) {
        byte[] text = raw.replace('+', ' ').getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int index = 0; index < text.length; index++) {
            if (text[index] == '%'
                    && index + 2 < text.length
                    && hex(text[index + 1]) >= 0
                    && hex(text[index + 2]) >= 0) {
                out.write(hex(text[index + 1]) * 16 + hex(text[index + 2]));
                index += 2;
            } else {
                out.write(text[index]);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(byte value) {
        return Character.digit((char) value, 16);
    }

    /** The first value given for {@code name}, or {@code fallback}. */
    public String one(String name, String fallback) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }

    /** The first value given for {@code name}, or an empty string. */
    public String one(String name) {
        return one(name, "");
    }

    /** The bearer token, or an empty string when there is none. */
    public String token() {
        String raw = headers.getOrDefault("authorization", "");
        return raw.startsWith("Bearer ") ? raw.substring("Bearer ".length()) : "";
    }
}
