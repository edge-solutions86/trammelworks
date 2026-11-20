package io.github.rkaushikdev.trammelworks.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.IngestError;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.NanoTime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IngestCheckpointTest {

    static final String KEY = "US-TX-0417_payment-kernel_0";

    @TempDir
    Path tmp;

    CheckpointStore store;

    @BeforeEach
    void setUp() {
        store = new CheckpointStore(tmp.resolve("ckpt"));
    }

    static Checkpoint checkpoint() {
        return new Checkpoint(
                KEY,
                "/var/log/pods/x/payment-kernel/0.log",
                2049,
                8675309,
                40960,
                NanoTime.parse("2025-03-04T11:30:00Z"));
    }

    static Checkpoint withKey(String key) {
        Checkpoint base = checkpoint();
        return new Checkpoint(key, base.path(), base.device(), base.inode(), base.offset(), base.updatedAt());
    }

    Path file() {
        return store.directory().resolve(KEY + ".ckpt");
    }

    @ParameterizedTest
    @ValueSource(strings = {KEY, "a", "a.b-c_d", "0"})
    void checkKeyAcceptsAFilenameSafeKey(String key) {
        assertThat(CheckpointStore.checkKey(key)).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "..", "../escape", "with/slash", ".hidden", "with space"})
    void checkKeyRejectsAnythingThatCouldEscape(String key) {
        assertThatThrownBy(() -> CheckpointStore.checkKey(key))
                .isInstanceOf(IngestError.class)
                .hasMessageContaining("malformed checkpoint key");
    }

    @Test
    void loadingAnAbsentCheckpointGivesNone() {
        assertThat(store.load(KEY)).isEmpty();
    }

    @Test
    void aSavedCheckpointComesBack() {
        store.save(checkpoint());
        assertThat(store.load(KEY)).contains(checkpoint());
    }

    @Test
    void saveCreatesTheDirectory() {
        new CheckpointStore(tmp.resolve("deep").resolve("ckpt")).save(checkpoint());
        assertThat(tmp.resolve("deep").resolve("ckpt")).isDirectory();
    }

    @Test
    void saveLeavesNoTemporaryFiles() throws IOException {
        store.save(checkpoint());
        try (Stream<Path> found = Files.list(store.directory())) {
            assertThat(found.map(path -> path.getFileName().toString())).allMatch(name -> name.endsWith(".ckpt"));
        }
    }

    @Test
    void savingTwiceReplaces() {
        store.save(checkpoint());
        store.save(checkpoint().withOffset(81920));
        assertThat(store.load(KEY).orElseThrow().offset()).isEqualTo(81920);
    }

    @Test
    void aNegativeOffsetIsRefused() {
        assertThatThrownBy(() -> store.save(checkpoint().withOffset(-1)))
                .isInstanceOf(IngestError.class)
                .hasMessageContaining("negative offset");
    }

    @Test
    void anOffsetOfZeroIsFine() {
        store.save(checkpoint().withOffset(0));
        assertThat(store.load(KEY).orElseThrow().offset()).isZero();
    }

    @Test
    void theNanosecondStampSurvives() {
        Checkpoint base = checkpoint();
        store.save(new Checkpoint(
                KEY,
                base.path(),
                base.device(),
                base.inode(),
                base.offset(),
                NanoTime.parse("2025-03-04T11:30:00.000000042Z")));
        assertThat(store.load(KEY).orElseThrow().updatedAt().nanosInSecond()).isEqualTo(42);
    }

    @Test
    void identityIsRebuiltFromTheStoredPair() {
        assertThat(checkpoint().identity()).isEqualTo(new FileIdentity(2049, 8675309));
    }

    @Test
    void matchesIsTrueForTheSameFile() {
        assertThat(checkpoint().matches(new FileIdentity(2049, 8675309))).isTrue();
    }

    @Test
    void matchesIsFalseAfterARotation() {
        assertThat(checkpoint().matches(new FileIdentity(2049, 42))).isFalse();
    }

    @Test
    void aCorruptCheckpointReadsAsAbsent() throws IOException {
        store.save(checkpoint());
        Files.writeString(file(), "{not json");
        assertThat(store.load(KEY)).isEmpty();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void aTruncatedCheckpointReadsAsAbsent() throws IOException {
        store.save(checkpoint());
        Files.writeString(file(), Files.readString(file()).substring(0, 20));
        assertThat(store.load(KEY)).isEmpty();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void aCheckpointThatIsNotAnObjectReadsAsAbsent() throws IOException {
        Files.createDirectories(store.directory());
        Files.writeString(file(), "[1,2,3]");
        assertThat(store.load(KEY)).isEmpty();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void aCheckpointMissingAFieldReadsAsAbsent() throws IOException {
        Files.createDirectories(store.directory());
        Map<String, Object> payload = checkpoint().asDict();
        payload.remove("offset");
        Files.writeString(file(), Json.dumps(payload));
        assertThat(store.load(KEY)).isEmpty();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void aFutureVersionReadsAsAbsent() throws IOException {
        Files.createDirectories(store.directory());
        Map<String, Object> payload = checkpoint().asDict();
        payload.put("version", 99);
        Files.writeString(file(), Json.dumps(payload));
        assertThat(store.load(KEY)).isEmpty();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void fromDictNamesTheVersionItRefused() {
        Map<String, Object> payload = checkpoint().asDict();
        payload.put("version", 7);
        assertThatThrownBy(() -> Checkpoint.fromDict(payload))
                .isInstanceOf(IngestError.class)
                .hasMessageContaining("unknown checkpoint version");
    }

    @Test
    void theStoredFormRoundTrips() {
        assertThat(Checkpoint.fromDict(checkpoint().asDict())).isEqualTo(checkpoint());
    }

    @Test
    void forgetRemovesACheckpoint() {
        store.save(checkpoint());
        assertThat(store.forget(KEY)).isTrue();
        assertThat(store.load(KEY)).isEmpty();
    }

    @Test
    void forgetReportsWhenThereWasNothing() {
        assertThat(store.forget(KEY)).isFalse();
    }

    @Test
    void keysListsWhatIsStored() {
        store.save(checkpoint());
        store.save(withKey("US-TX-0417_tank-gauge_0"));
        assertThat(store.keys()).containsExactly("US-TX-0417_payment-kernel_0", "US-TX-0417_tank-gauge_0");
    }

    @Test
    void keysOfAnAbsentDirectoryIsEmpty() {
        assertThat(new CheckpointStore(tmp.resolve("nope")).keys()).isEqualTo(List.of());
    }

    @Test
    void sweepRemovesOnlyWhatIsNoLongerFollowed() {
        store.save(checkpoint());
        store.save(withKey("US-TX-0417_tank-gauge_0"));
        assertThat(store.sweep(Set.of("US-TX-0417_payment-kernel_0"))).isEqualTo(1);
        assertThat(store.keys()).containsExactly("US-TX-0417_payment-kernel_0");
    }

    @Test
    void sweepWithEverythingLiveRemovesNothing() {
        store.save(checkpoint());
        assertThat(store.sweep(Set.of(KEY))).isZero();
    }
}
