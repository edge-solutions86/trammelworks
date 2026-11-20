package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.Blake2b;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.ShipError;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One request's worth of records.
 *
 * <p>Delivery is at least once, so the collector sees some batches twice, and the job here is to
 * make the duplicate recognisable. The batch id is derived from what is in the batch, not from a
 * counter or a clock: the same records cut the same way always produce the same id, so a retry
 * after a lost acknowledgement arrives with the id the collector already has.
 *
 * @param site the site the records came from
 * @param records the records, as they sat in the spool
 * @param source the segment they were cut from
 * @param firstIndex where in that segment the first one sat
 */
public record Batch(String site, List<Map<String, Object>> records, String source, int firstIndex) {

    /** The batch id's digest size, in bytes. */
    public static final int BATCH_ID_BYTES = 12;

    private static final byte[] FIELD = {0x1f};
    private static final byte[] RECORD = {0x1e};

    /** Refuses an empty batch and copies the records. */
    public Batch {
        if (records.isEmpty()) {
            throw new ShipError("a batch cannot be empty");
        }
        records = List.copyOf(records);
    }

    /** A batch with no source, starting at the first record. */
    public Batch(String site, List<Map<String, Object>> records) {
        this(site, records, "", 0);
    }

    /** A batch from a named source, starting at the first record. */
    public Batch(String site, List<Map<String, Object>> records, String source) {
        this(site, records, source, 0);
    }

    /** How many records. */
    public int count() {
        return records.size();
    }

    /** Where in the segment the last one sat. */
    public int lastIndex() {
        return firstIndex + count() - 1;
    }

    /** A name for this batch that only depends on what is in it. */
    public String batchId() {
        Blake2b digest = new Blake2b(BATCH_ID_BYTES)
                .update(site)
                .update(FIELD)
                .update(source)
                .update(FIELD);
        for (Map<String, Object> record : records) {
            digest.update(canonical(record)).update(RECORD);
        }
        return digest.hexdigest();
    }

    /** The body the collector receives. */
    public Map<String, Object> payload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("site", site);
        out.put("batch_id", batchId());
        out.put("source", source);
        out.put("count", count());
        out.put("records", new ArrayList<>(records));
        return out;
    }

    /** The bytes on the wire. */
    public byte[] encode() {
        return Json.dumps(payload(), Json.Style.compact()).getBytes(StandardCharsets.UTF_8);
    }

    /** How many bytes the batch costs on the wire. */
    public int wireBytes() {
        return encode().length;
    }

    static byte[] canonical(Map<String, ?> record) {
        return Json.dumps(record, Json.Style.compact()).getBytes(StandardCharsets.UTF_8);
    }
}
