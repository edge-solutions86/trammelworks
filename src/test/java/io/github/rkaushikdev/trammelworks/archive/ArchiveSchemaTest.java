package io.github.rkaushikdev.trammelworks.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.StanchionError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveSchemaTest {

    static final String INSERT = "INSERT INTO records (key, site, unit, klass, stream, observed_ns, message, stored_ns)"
            + " VALUES (?, 'US-TX-0417', 'pump-bridge', 'standard', 'stdout', 1, 'x', 1)";

    @TempDir
    Path tmp;

    Path archivePath;

    @BeforeEach
    void setUp() {
        archivePath = ArchiveFixtures.archivePath(tmp);
    }

    static Set<String> names(ArchiveDb db, String type) {
        return db.query("SELECT name FROM sqlite_master WHERE type=?", type).stream()
                .map(row -> row.getString("name"))
                .collect(Collectors.toSet());
    }

    @Test
    void openingCreatesTheFile() {
        Schema.openArchive(archivePath).close();
        assertThat(archivePath).exists();
    }

    @Test
    void openingCreatesTheParentDirectory() {
        Schema.openArchive(archivePath).close();
        assertThat(archivePath.getParent()).isDirectory();
    }

    @Test
    void theTablesAreThere() {
        try (ArchiveDb db = Schema.openArchive(archivePath)) {
            assertThat(names(db, "table")).contains("records", "holds", "meta");
        }
    }

    @Test
    void theIndexesAreThere() {
        try (ArchiveDb db = Schema.openArchive(archivePath)) {
            // Named for the expression they cover, because that is what the reads order by.
            assertThat(names(db, "index"))
                    .contains("records_by_best_time", "records_by_unit_time", "records_by_class_time");
        }
    }

    @Test
    void theVersionIsStamped() {
        try (ArchiveDb db = Schema.openArchive(archivePath)) {
            assertThat(Schema.readVersion(db)).isEqualTo(Schema.SCHEMA_VERSION);
        }
    }

    @Test
    void openingTwiceIsHarmless() {
        Schema.openArchive(archivePath).close();
        try (ArchiveDb db = Schema.openArchive(archivePath)) {
            assertThat(Schema.readVersion(db)).isEqualTo(Schema.SCHEMA_VERSION);
        }
    }

    @Test
    void theWriteAheadLogIsOn() {
        try (ArchiveDb db = Schema.openArchive(archivePath)) {
            assertThat(db.queryOne("PRAGMA journal_mode").orElseThrow().get(0).toString())
                    .isEqualToIgnoringCase("wal");
        }
    }

    @Test
    void theBusyTimeoutIsSet() {
        try (ArchiveDb db = Schema.openArchive(archivePath)) {
            assertThat(db.queryOne("PRAGMA busy_timeout").orElseThrow().get(0)).isEqualTo(5000L);
        }
    }

    @Test
    void aRecordKeyIsUnique() {
        try (ArchiveDb db = Schema.openArchive(archivePath)) {
            db.execute(INSERT, "abc");
            assertThatThrownBy(() -> db.execute(INSERT, "abc"))
                    .isInstanceOfSatisfying(
                            SqlFailure.class, exc -> assertThat(exc.kind()).isSameAs(SqlFailure.Kind.INTEGRITY));
        }
    }

    @Test
    void aMissingVersionRowReadsAsZero() {
        try (ArchiveDb db = Schema.connect(tmp.resolve("bare.sqlite3"))) {
            assertThat(Schema.readVersion(db)).isZero();
        }
    }

    @Test
    void aNonNumericVersionIsRefused() {
        try (ArchiveDb db = Schema.openArchive(archivePath)) {
            db.execute("UPDATE meta SET value = 'one' WHERE name = 'schema_version'");
            assertThatThrownBy(() -> Schema.readVersion(db))
                    .isInstanceOf(StanchionError.class)
                    .hasMessageContaining("version is not a number");
        }
    }

    @Test
    void anArchiveFromTheFutureIsRefused() {
        try (ArchiveDb db = Schema.openArchive(archivePath)) {
            db.execute("UPDATE meta SET value = '99' WHERE name = 'schema_version'");
        }
        assertThatThrownBy(() -> Schema.openArchive(archivePath))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("newer stanchion");
    }

    @Test
    void aFileThatIsNotADatabaseIsRefused() throws IOException {
        Path target = tmp.resolve("junk.sqlite3");
        Files.writeString(target, "this is not a database".repeat(10));
        assertThatThrownBy(() -> Schema.openArchive(target)).isInstanceOf(StanchionError.class);
    }

    @Test
    void readOnlyRefusesAMissingFile() {
        assertThatThrownBy(() -> Schema.openArchive(archivePath, true))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void readOnlyCanRead() {
        Schema.openArchive(archivePath).close();
        try (ArchiveDb db = Schema.openArchive(archivePath, true)) {
            assertThat(Schema.readVersion(db)).isEqualTo(Schema.SCHEMA_VERSION);
        }
    }

    @Test
    void readOnlyCannotWrite() {
        Schema.openArchive(archivePath).close();
        try (ArchiveDb db = Schema.openArchive(archivePath, true)) {
            assertThatThrownBy(() -> db.execute("DELETE FROM records"))
                    .isInstanceOfSatisfying(
                            SqlFailure.class, exc -> assertThat(exc.kind()).isSameAs(SqlFailure.Kind.OPERATIONAL));
        }
    }

    @Test
    void createIsIdempotent() {
        try (ArchiveDb db = Schema.openArchive(archivePath)) {
            Schema.create(db);
            Schema.create(db);
            assertThat(Schema.readVersion(db)).isEqualTo(Schema.SCHEMA_VERSION);
        }
    }

    @Test
    void describeReportsAnEmptyArchive() {
        try (ArchiveDb db = Schema.openArchive(archivePath)) {
            ArchiveInfo info = Schema.describe(db, archivePath);
            assertThat(info.records()).isZero();
            assertThat(info.empty()).isTrue();
            assertThat(info.version()).isEqualTo(Schema.SCHEMA_VERSION);
        }
    }

    @Test
    void describeCountsTheRecords() {
        try (ArchiveDb db = Schema.openArchive(archivePath)) {
            db.execute(INSERT, "k");
            assertThat(Schema.describe(db, archivePath).records()).isEqualTo(1);
        }
    }

    @Test
    void aHoldCanBeRecorded() {
        try (ArchiveDb db = Schema.openArchive(archivePath)) {
            db.execute("INSERT INTO holds (name, reason, placed_ns) VALUES ('case-41', 'dispute', 1)");
            Row row = db.queryOne("SELECT * FROM holds").orElseThrow();
            assertThat(row.get("name")).isEqualTo("case-41");
            assertThat(row.get("until_ns")).isNull();
        }
    }

    @Test
    void aSchemaVersionOneArchiveStillOpens() throws IOException {
        // A site upgraded in place keeps its archive: a schema version 1 file, with its old
        // indexes and three stored records, must open, migrate, read and take new records.
        Path legacy = tmp.resolve("schema-v1.sqlite3");
        try (var source = ArchiveSchemaTest.class.getResourceAsStream("/archive/schema-v1-records.sqlite3")) {
            Files.copy(source, legacy);
        }
        try (ArchiveDb db = Schema.openArchive(legacy)) {
            Repository repo = new Repository(db, ArchiveFixtures.TABLE);
            assertThat(Schema.readVersion(db)).isEqualTo(Schema.SCHEMA_VERSION);
            assertThat(repo.count()).isEqualTo(3);
            assertThat(repo.countsByClass()).isEqualTo(java.util.Map.of("payment", 1L, "standard", 2L));
            assertThat(repo.between(
                                    new io.github.rkaushikdev.trammelworks.NanoTime(0),
                                    io.github.rkaushikdev.trammelworks.NanoTime.parse("2100-01-01T00:00:00Z"))
                            .get(0)
                            .labels())
                    .isEqualTo(java.util.Map.of("lane", "3"));
            // The dedupe key is the collector's, so the record already stored is not new here.
            io.github.rkaushikdev.trammelworks.LogRecord same = ArchiveFixtures.record(
                            "payment-kernel",
                            io.github.rkaushikdev.trammelworks.NanoTime.parse("2025-03-04T12:00:00Z"),
                            "auth approved ref 112233")
                    .withLabel("lane", "3");
            assertThat(repo.add(same, io.github.rkaushikdev.trammelworks.NanoTime.parse("2025-03-05T00:00:00Z")))
                    .isFalse();
            assertThat(Schema.indexNames(db)).contains("records_by_best_time").doesNotContain("records_by_time");
        }
    }
}
