package io.github.rkaushikdev.trammelworks.archive;

import io.github.rkaushikdev.trammelworks.PosixPath;
import io.github.rkaushikdev.trammelworks.StanchionError;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.sqlite.SQLiteConfig;

/**
 * The on site record store: the copy that stays after the spool has shipped.
 *
 * <p>SQLite, because the alternative on a controller with 2 GB of RAM and an SD card is a file
 * format we would have to write ourselves. WAL, so a support query does not block the writer;
 * synchronous NORMAL, because the last transaction is also in the spool; a busy timeout, because
 * the collector and the retention sweep really do both write. The schema is flat, labels as JSON,
 * and the indexes are on the expression every read orders by.
 */
public final class Schema {

    /** The schema this build writes. */
    public static final int SCHEMA_VERSION = 2;

    /** How long a writer waits for the lock. */
    public static final int BUSY_TIMEOUT_MS = 5000;

    /**
     * Every read orders by this and most filter on it, so it is what the indexes are built on. An
     * index on a column cannot serve an expression over that column, which is how the first
     * version ended up scanning.
     */
    public static final String BEST_TIME = "COALESCE(corrected_ns, observed_ns)";

    /** The indexes the reads use. */
    public static final List<String> INDEXES = List.of(
            "CREATE INDEX IF NOT EXISTS records_by_best_time ON records (" + BEST_TIME + ")",
            "CREATE INDEX IF NOT EXISTS records_by_unit_time ON records (unit, " + BEST_TIME + ")",
            "CREATE INDEX IF NOT EXISTS records_by_class_time ON records (klass, " + BEST_TIME + ")",
            // Kept because the sweeper asks what was stored when, a different question.
            "CREATE INDEX IF NOT EXISTS records_by_stored ON records (stored_ns)");

    /** Indexes an earlier version built that nothing can use, dropped on upgrade. */
    public static final List<String> RETIRED_INDEXES =
            List.of("records_by_time", "records_by_unit", "records_by_class");

    static final String RECORDS =
            """
            CREATE TABLE IF NOT EXISTS records (
                id            INTEGER PRIMARY KEY,
                key           TEXT    NOT NULL UNIQUE,
                site          TEXT    NOT NULL,
                unit          TEXT    NOT NULL,
                klass         TEXT    NOT NULL,
                stream        TEXT    NOT NULL,
                observed_ns   INTEGER NOT NULL,
                corrected_ns  INTEGER,
                confidence    TEXT    NOT NULL DEFAULT 'unknown',
                message       TEXT    NOT NULL,
                labels        TEXT    NOT NULL DEFAULT '{}',
                stored_ns     INTEGER NOT NULL
            )""";

    static final String HOLDS =
            """
            CREATE TABLE IF NOT EXISTS holds (
                name        TEXT PRIMARY KEY,
                reason      TEXT NOT NULL,
                placed_ns   INTEGER NOT NULL,
                from_ns     INTEGER,
                until_ns    INTEGER
            )""";

    static final String META =
            """
            CREATE TABLE IF NOT EXISTS meta (
                name  TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )""";

    /** The pragmas every connection gets; a read only one skips the journal mode. */
    public static final List<String> PRAGMAS = List.of(
            "PRAGMA journal_mode=WAL",
            "PRAGMA synchronous=NORMAL",
            "PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS,
            "PRAGMA foreign_keys=ON");

    private Schema() {}

    /** Open for writing, creating the directory as needed. */
    public static ArchiveDb connect(Path path) {
        return connect(path, false);
    }

    /** Open the archive, applying the pragmas every connection needs. */
    public static ArchiveDb connect(Path path, boolean readOnly) {
        SQLiteConfig config = new SQLiteConfig();
        if (readOnly) {
            if (!Files.exists(path)) {
                throw new StanchionError("archive does not exist", path.toString());
            }
            config.setReadOnly(true);
        } else {
            try {
                Files.createDirectories(path.toAbsolutePath().getParent());
            } catch (IOException exc) {
                throw new UncheckedIOException(exc);
            }
        }
        Connection connection;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + PosixPath.asPosix(path), config.toProperties());
        } catch (SQLException exc) {
            throw new StanchionError("archive is not a database", exc.getMessage());
        }
        ArchiveDb db = new ArchiveDb(connection, path);
        try {
            for (String pragma : PRAGMAS) {
                if (readOnly && pragma.contains("journal_mode")) {
                    continue;
                }
                db.execute(pragma);
            }
        } catch (SqlFailure exc) {
            // The first pragma is where a file that is not a database announces itself.
            db.close();
            throw new StanchionError("archive is not a database", exc.getMessage());
        }
        return db;
    }

    /** Build the tables if they are not there. */
    public static void create(ArchiveDb db) {
        db.transaction(() -> {
            db.execute(RECORDS);
            INDEXES.forEach(db::execute);
            db.execute(HOLDS);
            db.execute(META);
            db.execute(
                    "INSERT OR IGNORE INTO meta (name, value) VALUES ('schema_version', ?)",
                    Integer.toString(SCHEMA_VERSION));
        });
    }

    /**
     * Bring an older archive up to the current schema. Returns the version it left. Only ever adds
     * indexes and drops ones nothing uses: this runs at startup on a box whose only job is to keep
     * collecting, so there is no table rewrite and there must not be.
     */
    public static int migrate(ArchiveDb db) {
        int version = readVersion(db);
        if (version >= SCHEMA_VERSION) {
            return version;
        }
        db.transaction(() -> {
            for (String name : RETIRED_INDEXES) {
                db.execute("DROP INDEX IF EXISTS " + name);
            }
            INDEXES.forEach(db::execute);
            db.execute(
                    "INSERT OR REPLACE INTO meta (name, value) VALUES ('schema_version', ?)",
                    Integer.toString(SCHEMA_VERSION));
        });
        return SCHEMA_VERSION;
    }

    /** Every index on the records table, for the doctor and the tests. */
    public static List<String> indexNames(ArchiveDb db) {
        return db
                .query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'records'"
                        + " AND name NOT LIKE 'sqlite_%' ORDER BY name")
                .stream()
                .map(row -> row.getString("name"))
                .toList();
    }

    /** The schema version this file was written with; zero for a file with no meta table. */
    public static int readVersion(ArchiveDb db) {
        Optional<Row> row;
        try {
            row = db.queryOne("SELECT value FROM meta WHERE name = 'schema_version'");
        } catch (SqlFailure exc) {
            if (exc.kind() == SqlFailure.Kind.OPERATIONAL) {
                // No meta table at all: an empty file rather than a damaged one.
                return 0;
            }
            throw new StanchionError("archive is not readable", exc.getMessage());
        }
        if (row.isEmpty()) {
            return 0;
        }
        String value = row.get().getString("value");
        try {
            return Integer.parseInt(io.github.rkaushikdev.trammelworks.Text.strip(String.valueOf(value)));
        } catch (NumberFormatException exc) {
            throw new StanchionError("archive version is not a number");
        }
    }

    /** Open for writing: create if needed, migrate, and refuse a file from a future version. */
    public static ArchiveDb openArchive(Path path) {
        return openArchive(path, false);
    }

    /** Open, create if needed, and refuse a file from a future version. */
    public static ArchiveDb openArchive(Path path, boolean readOnly) {
        ArchiveDb db = connect(path, readOnly);
        if (!readOnly) {
            create(db);
            migrate(db);
        }
        int version = readVersion(db);
        if (version > SCHEMA_VERSION) {
            db.close();
            throw new StanchionError("archive was written by a newer stanchion", Integer.toString(version));
        }
        return db;
    }

    /** What an opened archive says about itself. */
    public static ArchiveInfo describe(ArchiveDb db, Path path) {
        long count =
                db.queryOne("SELECT count(*) AS n FROM records").orElseThrow().getLong("n");
        return new ArchiveInfo(path, readVersion(db), count);
    }
}
