package io.github.rkaushikdev.trammelworks.archive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The indexes have to match the expression the queries use. Every read orders by the corrected time
 * where there is one, and SQLite cannot use an index on a column to serve an expression over it,
 * so these pin the query plans rather than the index names: a rename is fine, a scan is not.
 */
class ArchiveIndexesTest {

    static final NanoTime NOW = NanoTime.parse("2025-06-10T12:00:00Z");
    static final String ORDER = Repository.ORDER_COLUMN;
    static final List<String> OLD_INDEXES = List.of(
            "CREATE INDEX IF NOT EXISTS records_by_time ON records (observed_ns)",
            "CREATE INDEX IF NOT EXISTS records_by_unit ON records (unit, observed_ns)",
            "CREATE INDEX IF NOT EXISTS records_by_class ON records (klass, observed_ns)");

    @TempDir
    Path tmp;

    Path path;
    ArchiveDb connection;

    @BeforeEach
    void setUp() {
        path = ArchiveFixtures.archivePath(tmp);
    }

    @AfterEach
    void tearDown() {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    ArchiveDb fresh() {
        connection = Schema.openArchive(path);
        return connection;
    }

    static void fill(ArchiveDb db) {
        fill(db, 50);
    }

    static void fill(ArchiveDb db, int count) {
        List<LogRecord> records = IntStream.range(0, count)
                .mapToObj(index -> ArchiveFixtures.record(
                        index % 2 == 1 ? "pump-bridge" : "price-sign", NOW.shifted(index), "line " + index))
                .toList();
        new Repository(db).addAll(records, NOW);
    }

    /** An archive as an earlier version of this software left it. */
    ArchiveDb makeOld() {
        connection = Schema.connect(path);
        Schema.create(connection);
        ArchiveDb db = connection;
        db.transaction(() -> {
            for (String name : List.of("records_by_best_time", "records_by_unit_time", "records_by_class_time")) {
                db.execute("DROP INDEX IF EXISTS " + name);
            }
            OLD_INDEXES.forEach(db::execute);
            db.execute("INSERT OR REPLACE INTO meta (name, value) VALUES ('schema_version', '1')");
        });
        return db;
    }

    @Test
    void aFreshArchiveIsAtTheCurrentVersion() {
        assertThat(Schema.readVersion(fresh())).isEqualTo(Schema.SCHEMA_VERSION);
    }

    @Test
    void aFreshArchiveHasTheIndexesTheQueriesNeed() {
        assertThat(Schema.indexNames(fresh()))
                .contains("records_by_best_time", "records_by_unit_time", "records_by_class_time");
    }

    @Test
    void aFreshArchiveCarriesNoneOfTheRetiredIndexes() {
        assertThat(Schema.indexNames(fresh())).doesNotContainAnyElementsOf(Schema.RETIRED_INDEXES);
    }

    @Test
    void aTimeWindowUsesAnIndex() {
        ArchiveDb db = fresh();
        fill(db);
        assertThat(ArchiveFixtures.plan(db, "SELECT * FROM records WHERE " + ORDER + " > 5"))
                .anyMatch(step -> step.contains("records_by_best_time"));
    }

    @Test
    void orderingByTimeNeedsNoTemporarySort() {
        // The temp B-tree is the expensive half on an SD card.
        ArchiveDb db = fresh();
        fill(db);
        List<String> steps = ArchiveFixtures.plan(
                db, "SELECT * FROM records WHERE " + ORDER + " > 5 ORDER BY " + ORDER + " ASC, id ASC");
        assertThat(steps)
                .noneMatch(step -> step.toUpperCase(java.util.Locale.ROOT).contains("TEMP B-TREE"));
    }

    @Test
    void aUnitFilterUsesItsOwnIndex() {
        ArchiveDb db = fresh();
        fill(db);
        assertThat(ArchiveFixtures.plan(
                        db, "SELECT * FROM records WHERE unit = ? ORDER BY " + ORDER + " ASC, id ASC", "pump-bridge"))
                .anyMatch(step -> step.contains("records_by_unit_time"));
    }

    @Test
    void aClassFilterUsesItsOwnIndex() {
        ArchiveDb db = fresh();
        fill(db);
        assertThat(ArchiveFixtures.plan(
                        db, "SELECT * FROM records WHERE klass = ? ORDER BY " + ORDER + " ASC", "standard"))
                .anyMatch(step -> step.contains("records_by_class_time"));
    }

    @Test
    void theCursorPredicateUsesAnIndex() {
        ArchiveDb db = fresh();
        fill(db);
        List<String> steps = ArchiveFixtures.plan(
                db,
                "SELECT * FROM records WHERE ((" + ORDER + " > ?) OR (" + ORDER + " = ? AND id > ?)) ORDER BY " + ORDER
                        + " ASC, id ASC",
                1,
                1,
                1);
        assertThat(steps).noneMatch("SCAN records"::equals);
    }

    @Test
    void anOldArchiveReportsTheOldVersion() {
        assertThat(Schema.readVersion(makeOld())).isEqualTo(1);
    }

    @Test
    void anOldArchiveMigrates() {
        ArchiveDb db = makeOld();
        assertThat(Schema.migrate(db)).isEqualTo(Schema.SCHEMA_VERSION);
        assertThat(Schema.readVersion(db)).isEqualTo(Schema.SCHEMA_VERSION);
    }

    @Test
    void migratingAddsTheNewIndexes() {
        ArchiveDb db = makeOld();
        Schema.migrate(db);
        assertThat(Schema.indexNames(db)).contains("records_by_best_time");
    }

    @Test
    void migratingDropsTheOnesNothingCanUse() {
        // An index that is never read is still written on every insert, and on this hardware that
        // is the half that costs.
        ArchiveDb db = makeOld();
        assertThat(Schema.indexNames(db)).containsAnyElementsOf(Schema.RETIRED_INDEXES);
        Schema.migrate(db);
        assertThat(Schema.indexNames(db)).doesNotContainAnyElementsOf(Schema.RETIRED_INDEXES);
    }

    @Test
    void migratingKeepsTheRecords() {
        ArchiveDb db = makeOld();
        fill(db, 30);
        Schema.migrate(db);
        assertThat(new Repository(db).count()).isEqualTo(30);
    }

    @Test
    void migratingTwiceChangesNothing() {
        ArchiveDb db = makeOld();
        Schema.migrate(db);
        List<String> before = Schema.indexNames(db);
        assertThat(Schema.migrate(db)).isEqualTo(Schema.SCHEMA_VERSION);
        assertThat(Schema.indexNames(db)).isEqualTo(before);
    }

    @Test
    void aCurrentArchiveIsLeftAlone() {
        ArchiveDb db = fresh();
        List<String> before = Schema.indexNames(db);
        assertThat(Schema.migrate(db)).isEqualTo(Schema.SCHEMA_VERSION);
        assertThat(Schema.indexNames(db)).isEqualTo(before);
    }

    @Test
    void openingAnOldArchiveMigratesIt() {
        makeOld().close();
        ArchiveDb db = fresh();
        assertThat(Schema.readVersion(db)).isEqualTo(Schema.SCHEMA_VERSION);
        assertThat(Schema.indexNames(db)).contains("records_by_best_time");
    }

    @Test
    void openingReadOnlyDoesNotTryToMigrate() {
        // A reader has no business changing the file, and on a site where the collector holds it
        // open a reader that tried would fail rather than read.
        makeOld().close();
        connection = Schema.openArchive(path, true);
        assertThat(Schema.readVersion(connection)).isEqualTo(1);
    }

    @Test
    void aMigratedArchiveAnswersTheSameRecords() {
        ArchiveDb db = makeOld();
        fill(db, 20);
        Schema.migrate(db);
        List<StoredRecord> found = new Repository(db).between(new NanoTime(0), NOW.shifted(1000));
        assertThat(found)
                .extracting(StoredRecord::message)
                .containsExactlyElementsOf(IntStream.range(0, 20)
                        .mapToObj(index -> "line " + index)
                        .toList());
    }

    @Test
    void aMigratedArchiveUsesTheNewIndex() {
        ArchiveDb db = makeOld();
        fill(db, 20);
        Schema.migrate(db);
        assertThat(ArchiveFixtures.plan(db, "SELECT * FROM records WHERE " + ORDER + " > 5"))
                .anyMatch(step -> step.contains("records_by_best_time"));
    }
}
