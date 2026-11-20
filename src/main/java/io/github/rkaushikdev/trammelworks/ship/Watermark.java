package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.ShipError;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Progress through one segment: the count of records the collector has acknowledged, from the
 * start. A count and not a set of ids, because batches are cut in order and the only thing that
 * can be resumed from is a prefix. It moves only on an acknowledgement; sending is not
 * acknowledging.
 */
public final class Watermark {

    private final String segment;
    private int total;
    private int acked;
    private int attempts;
    private int failed;

    /** A fresh watermark at zero. */
    public Watermark(String segment, int total) {
        this(segment, total, 0, 0, 0);
    }

    /** A watermark with everything given. */
    public Watermark(String segment, int total, int acked, int attempts, int failed) {
        if (total < 0) {
            throw new ShipError("a segment cannot hold a negative number of records");
        }
        if (acked < 0 || acked > total) {
            throw new ShipError("watermark is outside the segment", acked + " of " + total);
        }
        this.segment = segment;
        this.total = total;
        this.acked = acked;
        this.attempts = attempts;
        this.failed = failed;
    }

    /** The segment's file name. */
    public String segment() {
        return segment;
    }

    /** Records in the segment. */
    public int total() {
        return total;
    }

    /** Records acknowledged, from the start. */
    public int acked() {
        return acked;
    }

    /** Batches sent from this segment. */
    public int attempts() {
        return attempts;
    }

    /** Batches refused for good. */
    public int failed() {
        return failed;
    }

    void attempt() {
        attempts++;
    }

    void failure() {
        failed++;
    }

    /** Whether every record is acknowledged. */
    public boolean done() {
        return acked >= total;
    }

    /** Records still to be acknowledged. */
    public int outstanding() {
        return total - acked;
    }

    /** Move the watermark on by an acknowledged count. */
    public int advance(int count) {
        if (count < 0) {
            throw new ShipError("cannot acknowledge a negative count", Integer.toString(count));
        }
        if (acked + count > total) {
            throw new ShipError(
                    "acknowledgement runs past the end of the segment", acked + "+" + count + " > " + total);
        }
        acked += count;
        return acked;
    }

    /**
     * Shrink the segment under this watermark and start it again. Compaction is the only caller:
     * everything acknowledged has left the file, so what has been acknowledged of what is left is
     * zero.
     */
    public void resize(int newTotal) {
        if (newTotal < 0) {
            throw new ShipError("a segment cannot hold a negative number of records");
        }
        if (newTotal > total) {
            throw new ShipError("a sealed segment cannot grow", total + " then " + newTotal);
        }
        total = newTotal;
        acked = 0;
    }

    /** The stored form. */
    public Map<String, Object> asDict() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("segment", segment);
        out.put("total", total);
        out.put("acked", acked);
        out.put("attempts", attempts);
        out.put("failed", failed);
        return out;
    }

    /** Read the stored form. */
    public static Watermark fromDict(Map<String, ?> payload) {
        try {
            for (String name : new String[] {"segment", "total"}) {
                if (!payload.containsKey(name)) {
                    throw new ShipError("watermark is missing a field", Repr.of(name));
                }
            }
            return new Watermark(
                    Coerce.text(payload.get("segment")),
                    Coerce.toInt(payload.get("total")),
                    Coerce.toInt(payload.containsKey("acked") ? payload.get("acked") : 0),
                    Coerce.toInt(payload.containsKey("attempts") ? payload.get("attempts") : 0),
                    Coerce.toInt(payload.containsKey("failed") ? payload.get("failed") : 0));
        } catch (ConfigError exc) {
            throw new ShipError("watermark is missing a field", exc.getMessage());
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Watermark mark
                && segment.equals(mark.segment)
                && total == mark.total
                && acked == mark.acked
                && attempts == mark.attempts
                && failed == mark.failed;
    }

    @Override
    public int hashCode() {
        return Objects.hash(segment, total, acked, attempts, failed);
    }

    @Override
    public String toString() {
        return "Watermark[" + segment + " " + acked + "/" + total + "]";
    }
}
