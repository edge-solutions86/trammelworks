package io.github.rkaushikdev.trammelworks.ingest;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.IngestError;
import io.github.rkaushikdev.trammelworks.JsonFiles;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.StanchionError;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where one file was read up to.
 *
 * <p>The file identity is stored with the offset and both are checked on resume, because an offset
 * alone is meaningless after a rotation: the new file at that path is a different file.
 *
 * @param key the followed file's key
 * @param path the followed file
 * @param device its device when the checkpoint was taken
 * @param inode its inode when the checkpoint was taken
 * @param offset how far was parsed
 * @param updatedAt when the checkpoint was taken
 */
public record Checkpoint(String key, String path, long device, long inode, long offset, NanoTime updatedAt) {

    /** The stored form's version. */
    public static final int FORMAT_VERSION = 1;

    /** The identity this checkpoint was taken against. */
    public FileIdentity identity() {
        return new FileIdentity(device, inode);
    }

    /** Whether a file is still the one this checkpoint describes. */
    public boolean matches(FileIdentity found) {
        return identity().equals(found);
    }

    /** The same checkpoint at another offset. */
    public Checkpoint withOffset(long value) {
        return new Checkpoint(key, path, device, inode, value, updatedAt);
    }

    /** The stored form. */
    public Map<String, Object> asDict() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", FORMAT_VERSION);
        out.put("key", key);
        out.put("path", path);
        out.put("device", device);
        out.put("inode", inode);
        out.put("offset", offset);
        out.put("updated_at", updatedAt.isoformat());
        return out;
    }

    /** Read the stored form. */
    public static Checkpoint fromDict(Map<String, ?> payload) {
        Object version = payload.get("version");
        if (!JsonFiles.sameNumber(version, FORMAT_VERSION)) {
            throw new IngestError("unknown checkpoint version", Coerce.text(version));
        }
        try {
            return new Checkpoint(
                    Coerce.text(field(payload, "key")),
                    Coerce.text(field(payload, "path")),
                    Coerce.toLong(field(payload, "device")),
                    Coerce.toLong(field(payload, "inode")),
                    Coerce.toLong(field(payload, "offset")),
                    NanoTime.parse(Coerce.text(field(payload, "updated_at"))));
        } catch (StanchionError exc) {
            throw new IngestError("checkpoint is missing a field", exc.getMessage());
        }
    }

    private static Object field(Map<String, ?> payload, String name) {
        if (!payload.containsKey(name)) {
            throw new IngestError(Repr.of(name));
        }
        return payload.get(name);
    }
}
