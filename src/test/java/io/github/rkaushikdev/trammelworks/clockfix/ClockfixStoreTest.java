package io.github.rkaushikdev.trammelworks.clockfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.NanoTime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClockfixStoreTest {

    static final String BOOT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d";
    static final String OTHER = "aa".repeat(16);

    @TempDir
    Path tmp;

    ClockStore store;

    @BeforeEach
    void setUp() {
        store = new ClockStore(tmp.resolve("state"));
    }

    static SyncEvent sync() {
        return sync(600);
    }

    static SyncEvent sync(long monotonicNs) {
        return new SyncEvent(
                BOOT,
                monotonicNs,
                NanoTime.parse("2025-03-04T11:59:53Z"),
                NanoTime.parse("2025-03-04T12:00:00.000000042Z"));
    }

    static SessionLedger filled() {
        SessionLedger ledger = new SessionLedger();
        ledger.addSync(sync());
        ledger.addSync(sync(1200));
        ledger.session(OTHER).observe(50);
        return ledger;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void aSyncRoundTripsThroughItsMapping() {
        assertThat(ClockStore.syncFromDict(ClockStore.syncToDict(sync()))).isEqualTo(sync());
    }

    @Test
    void theNanosecondPrecisionSurvives() {
        assertThat(ClockStore.syncFromDict(ClockStore.syncToDict(sync()))
                        .after()
                        .nanosInSecond())
                .isEqualTo(42);
    }

    @Test
    void theSourceSurvives() {
        SyncEvent event = new SyncEvent(BOOT, 1, new NanoTime(0), new NanoTime(0), "chrony");
        assertThat(ClockStore.syncFromDict(ClockStore.syncToDict(event)).source())
                .isEqualTo("chrony");
    }

    @Test
    void aMissingSourceDefaultsToNtp() {
        Map<String, Object> payload = ClockStore.syncToDict(sync());
        payload.remove("source");
        assertThat(ClockStore.syncFromDict(payload).source()).isEqualTo("ntp");
    }

    @Test
    void aSyncMissingAFieldIsRefused() {
        Map<String, Object> payload = ClockStore.syncToDict(sync());
        payload.remove("monotonic_ns");
        assertThatThrownBy(() -> ClockStore.syncFromDict(payload))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("missing a field");
    }

    @Test
    void aSessionRoundTripsWithItsSyncs() {
        SessionLedger restored = ClockStore.ledgerFromDict(ClockStore.ledgerToDict(filled()));
        assertThat(restored.session(BOOT).syncs()).hasSize(2);
        assertThat(restored.session(BOOT).lastMonotonicNs()).isEqualTo(1200);
    }

    @Test
    void aSessionMissingItsBootIdIsRefused() {
        assertThatThrownBy(() -> ClockStore.sessionFromDict(Map.of("syncs", List.of())))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("missing a field");
    }

    @Test
    void theLedgerKeepsItsOrder() {
        SessionLedger restored = ClockStore.ledgerFromDict(ClockStore.ledgerToDict(filled()));
        assertThat(restored.order()).containsExactly(BOOT, OTHER);
        assertThat(restored.current()).map(BootSession::bootId).contains(OTHER);
    }

    @Test
    void anUnknownVersionIsRefused() {
        Map<String, Object> payload = ClockStore.ledgerToDict(filled());
        payload.put("version", 99);
        assertThatThrownBy(() -> ClockStore.ledgerFromDict(payload))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("unknown clock ledger version");
    }

    @Test
    void loadingFromAnEmptyDirectoryGivesAnEmptyLedger() {
        assertThat(store.load().order()).isEmpty();
    }

    @Test
    void aSavedLedgerComesBack() {
        store.save(filled());
        SessionLedger restored = store.load();
        assertThat(restored.order()).containsExactly(BOOT, OTHER);
        assertThat(restored.session(BOOT).syncs()).hasSize(2);
    }

    @Test
    void saveCreatesTheDirectory() {
        ClockStore deep = new ClockStore(tmp.resolve("deep").resolve("state"));
        deep.save(new SessionLedger());
        assertThat(deep.path()).exists();
    }

    @Test
    void saveLeavesNoTemporaryFile() throws IOException {
        store.save(filled());
        try (Stream<Path> found = Files.list(store.directory())) {
            assertThat(found.map(path -> path.getFileName().toString())).containsExactly("clock.json");
        }
    }

    @Test
    void savingTwiceReplaces() {
        store.save(filled());
        SessionLedger fresh = new SessionLedger();
        fresh.session("bb".repeat(16));
        store.save(fresh);
        assertThat(store.load().order()).containsExactly("bb".repeat(16));
    }

    @Test
    void savePrunesToTheKeepCount() {
        ClockStore small = new ClockStore(tmp.resolve("state"), 1);
        small.save(filled());
        assertThat(small.load().order()).containsExactly(OTHER);
    }

    @Test
    void pruningAlsoShortensTheLedgerInMemory() {
        SessionLedger ledger = filled();
        new ClockStore(tmp.resolve("state"), 1).save(ledger);
        assertThat(ledger.order()).containsExactly(OTHER);
    }

    @Test
    void aKeepCountBelowOneIsRefused() {
        assertThatThrownBy(() -> new ClockStore(tmp, 0))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    void aCorruptFileReadsAsEmpty() throws IOException {
        store.save(filled());
        Files.writeString(store.path(), "{not json");
        assertThat(store.load().order()).isEmpty();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void aFileThatIsNotAnObjectReadsAsEmpty() throws IOException {
        Files.createDirectories(store.directory());
        Files.writeString(store.path(), "[1,2]");
        assertThat(store.load().order()).isEmpty();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void aFileWithABadSessionReadsAsEmpty() throws IOException {
        Files.createDirectories(store.directory());
        Map<String, Object> payload = Json.loadsObject(Json.dumps(ClockStore.ledgerToDict(filled())));
        Map<String, Object> session = object(((List<?>) payload.get("sessions")).get(0));
        object(((List<?>) session.get("syncs")).get(0)).put("boot_id", "nonsense");
        Files.writeString(store.path(), Json.dumps(payload));
        assertThat(store.load().order()).isEmpty();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void theFileIsIndentedSoAPersonCanReadIt() throws IOException {
        store.save(filled());
        assertThat(Files.readString(store.path()).chars().filter(c -> c == '\n').count())
                .isGreaterThan(5);
    }

    @Test
    void aDriftModelStillWorksAfterARoundTrip() {
        SessionLedger ledger = new SessionLedger();
        ledger.addSync(
                new SyncEvent(BOOT, 0, NanoTime.parse("2025-03-04T12:00:00Z"), NanoTime.parse("2025-03-04T12:00:00Z")));
        ledger.addSync(new SyncEvent(
                BOOT,
                600 * NanoTime.NS_PER_SECOND,
                NanoTime.parse("2025-03-04T12:09:50Z"),
                NanoTime.parse("2025-03-04T12:10:00Z")));
        store.save(ledger);
        DriftModel model = new DriftModel(store.load().session(BOOT));
        assertThat(model.correctionAt(300 * NanoTime.NS_PER_SECOND).offsetNs()).isEqualTo(5 * NanoTime.NS_PER_SECOND);
    }
}
