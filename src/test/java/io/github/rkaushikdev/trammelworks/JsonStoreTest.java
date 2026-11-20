package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonStoreTest {

    @TempDir
    Path tmp;

    static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    static List<Path> listing(Path directory) {
        try (Stream<Path> found = Files.list(directory)) {
            return found.toList();
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    @Test
    void aMappingIsWrittenAndReadBack() throws IOException {
        Path target = tmp.resolve("state").resolve("thing.json");
        JsonFiles.writeAtomic(target, Map.of("a", 1));
        assertThat(Json.loads(read(target))).isEqualTo(Map.of("a", 1L));
    }

    @Test
    void theDirectoryIsCreated() {
        JsonFiles.writeAtomic(tmp.resolve("deep").resolve("state").resolve("thing.json"), Map.of());
        assertThat(tmp.resolve("deep").resolve("state")).isDirectory();
    }

    @Test
    void noTemporaryFileIsLeftBehind() {
        Path target = tmp.resolve("state").resolve("thing.json");
        JsonFiles.writeAtomic(target, Map.of("a", 1));
        assertThat(listing(target.getParent()))
                .extracting(path -> path.getFileName().toString())
                .containsExactly("thing.json");
    }

    @Test
    void theTemporaryFileSharesTheDirectory() throws IOException {
        // A rename is only atomic within one filesystem, so the temporary file must not land in the
        // system temp directory.
        List<Path> seen = new ArrayList<>();
        Path target = tmp.resolve("state").resolve("thing.json");
        Files.createDirectories(target.getParent());
        Map<String, Object> watcher = new LinkedHashMap<>(Map.of("a", 1)) {
            @Override
            public Set<Map.Entry<String, Object>> entrySet() {
                seen.addAll(listing(target.getParent()));
                return super.entrySet();
            }
        };
        JsonFiles.writeAtomic(target, watcher);
        assertThat(seen).isNotEmpty().allMatch(item -> item.getParent().equals(target.getParent()));
    }

    @Test
    void writingTwiceReplaces() throws IOException {
        Path target = tmp.resolve("thing.json");
        JsonFiles.writeAtomic(target, Map.of("a", 1));
        JsonFiles.writeAtomic(target, Map.of("a", 2));
        assertThat(Json.loads(read(target))).isEqualTo(Map.of("a", 2L));
    }

    @Test
    void theWrittenFormIsSorted() throws IOException {
        Path target = tmp.resolve("thing.json");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("z", 1);
        payload.put("a", 2);
        JsonFiles.writeAtomic(target, payload);
        String body = read(target);
        assertThat(body.indexOf("\"a\"")).isLessThan(body.indexOf("\"z\""));
    }

    @Test
    void anIndentCanBeAskedFor() throws IOException {
        Path target = tmp.resolve("thing.json");
        JsonFiles.writeAtomic(target, Map.of("a", 1, "b", 2), 1);
        assertThat(read(target).lines().count()).isGreaterThan(1);
    }

    @Test
    void aFailureLeavesNoTemporaryFile() {
        Path target = tmp.resolve("thing.json");
        assertThatThrownBy(() -> JsonFiles.writeAtomic(target, Map.of("a", new Object())))
                .isInstanceOf(Json.JsonException.class);
        assertThat(listing(tmp)).isEmpty();
    }

    @Test
    void readingAMissingFileIsReported() {
        ReadResult found = JsonFiles.readJson(tmp.resolve("absent.json"));
        assertThat(found.missing()).isTrue();
        assertThat(found.usable()).isFalse();
    }

    @Test
    void readingADirectoryIsAnError() {
        assertThatThrownBy(() -> JsonFiles.readJson(tmp))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("state file unreadable");
    }

    @Test
    void readingBrokenJsonIsCorrupt() throws IOException {
        Path target = tmp.resolve("thing.json");
        Files.writeString(target, "{broken");
        ReadResult found = JsonFiles.readJson(target);
        assertThat(found.corrupt()).isTrue();
        assertThat(found.usable()).isFalse();
    }

    @Test
    void readingAJsonArrayIsCorrupt() throws IOException {
        Path target = tmp.resolve("thing.json");
        Files.writeString(target, "[1,2]");
        assertThat(JsonFiles.readJson(target).corrupt()).isTrue();
    }

    @Test
    void aWrongVersionIsCorrupt() {
        Path target = tmp.resolve("thing.json");
        JsonFiles.writeAtomic(target, Map.of("version", 9, "a", 1));
        assertThat(JsonFiles.readJson(target, 1).corrupt()).isTrue();
    }

    @Test
    void theRightVersionIsUsable() {
        Path target = tmp.resolve("thing.json");
        JsonFiles.writeAtomic(target, Map.of("version", 1, "a", 1));
        assertThat(JsonFiles.readJson(target, 1).usable()).isTrue();
    }

    @Test
    void aStoreRoundTrips() {
        JsonStore store = new JsonStore(tmp.resolve("thing.json"), 1);
        store.save(Map.of("a", 1));
        assertThat(store.load()).contains(Map.of("version", 1L, "a", 1L));
    }

    @Test
    void aStoreStampsTheVersion() throws IOException {
        JsonStore store = new JsonStore(tmp.resolve("thing.json"), 4);
        store.save(Map.of("a", 1));
        assertThat(Json.loadsObject(read(tmp.resolve("thing.json"))).get("version"))
                .isEqualTo(4L);
    }

    @Test
    void aStoreWithoutAVersionStampsNothing() throws IOException {
        JsonStore store = new JsonStore(tmp.resolve("thing.json"));
        store.save(Map.of("a", 1));
        assertThat(Json.loadsObject(read(tmp.resolve("thing.json")))).doesNotContainKey("version");
    }

    @Test
    void aStoreReportsNothingForAMissingFile() {
        assertThat(new JsonStore(tmp.resolve("absent.json")).load()).isEmpty();
    }

    @Test
    void aStoreCountsCorruption() throws IOException {
        JsonStore store = new JsonStore(tmp.resolve("thing.json"));
        Files.writeString(tmp.resolve("thing.json"), "{broken");
        assertThat(store.load()).isEmpty();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void aMissingFileIsNotCountedAsCorrupt() {
        JsonStore store = new JsonStore(tmp.resolve("absent.json"));
        store.load();
        assertThat(store.corrupt()).isZero();
    }

    @Test
    void forgettingRemovesTheFile() {
        JsonStore store = new JsonStore(tmp.resolve("thing.json"));
        store.save(Map.of("a", 1));
        assertThat(store.forget()).isTrue();
        assertThat(store.forget()).isFalse();
    }
}
