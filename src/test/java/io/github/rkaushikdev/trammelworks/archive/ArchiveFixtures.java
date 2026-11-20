package io.github.rkaushikdev.trammelworks.archive;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.spool.PriorityTable;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** What the archive tests share: the site's classes and a way to make records. */
final class ArchiveFixtures {

    static final String SITE = "US-TX-0417";
    static final PriorityTable TABLE = new PriorityTable(Map.of("payment-kernel", "payment", "price-sign", "chatter"));

    private ArchiveFixtures() {}

    static Path archivePath(Path tmp) {
        return tmp.resolve("archive").resolve("records.sqlite3");
    }

    static LogRecord record(String unit, NanoTime at, String message) {
        return new LogRecord(SITE, unit, Stream.STDOUT, at, message);
    }

    static Map<String, Long> counts(ArchiveDb db) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (Row row : db.query("SELECT klass, COUNT(*) AS n FROM records GROUP BY klass")) {
            out.put(row.getString("klass"), row.getLong("n"));
        }
        return out;
    }

    static List<String> plan(ArchiveDb db, String sql, Object... params) {
        return db.query("EXPLAIN QUERY PLAN " + sql, params).stream()
                .map(row -> String.valueOf(row.get(3)))
                .toList();
    }
}
