package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.cli.CliRunner;
import io.github.rkaushikdev.trammelworks.ship.Batch;
import io.github.rkaushikdev.trammelworks.ship.FileTransport;
import io.github.rkaushikdev.trammelworks.ship.Signer;
import io.github.rkaushikdev.trammelworks.ship.SigningKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReplayTest {

    static final byte[] SECRET = "k".repeat(SigningKey.MIN_SECRET_BYTES).getBytes(StandardCharsets.UTF_8);
    static final NanoTime NOW = NanoTime.parse("2025-04-11T08:30:00Z");
    static final String SITE = "US-TX-0417";

    @TempDir
    Path tmp;

    Path stick;
    SigningKey key;

    @BeforeEach
    void setUp() throws IOException {
        stick = Files.createDirectories(tmp.resolve("stick"));
        key = new SigningKey("tx-0417-2025", SECRET);
    }

    static Signer signerFor(SigningKey key) {
        ManualClock clock = new ManualClock();
        clock.setWall("2025-04-11T08:30:00Z");
        return new Signer(key, clock);
    }

    static Batch batch(int index) {
        List<Map<String, Object>> records = IntStream.range(0, 2)
                .mapToObj(item -> Maps.of("message", "line " + index + "-" + item))
                .toList();
        return new Batch(SITE, records, "seg-" + index);
    }

    /**
     * Write some batches, signed at the fixed test instant or at this one. The command line uses
     * the real clock and the signature window is ten days wide, so a batch signed at the fixed
     * instant is correctly refused by it; the command tests sign at the current instant.
     */
    List<Batch> writeBatches(SigningKey with, int howMany, boolean now) {
        Signer signer = null;
        if (with != null) {
            signer = now ? new Signer(with, new SystemClock()) : signerFor(with);
        }
        FileTransport transport = new FileTransport(stick, signer);
        List<Batch> made = new ArrayList<>();
        for (int index = 0; index < howMany; index++) {
            made.add(batch(index));
        }
        made.forEach(transport::send);
        return made;
    }

    List<Batch> writeBatches(SigningKey with, int howMany) {
        return writeBatches(with, howMany, false);
    }

    Path write(String name, String text) throws IOException {
        Path target = stick.resolve(name);
        Files.writeString(target, text, StandardCharsets.UTF_8);
        return target;
    }

    Path keyFile() throws IOException {
        Path target = tmp.resolve("site.key");
        Files.write(target, SECRET);
        return target;
    }

    static CliRunner.Result invoke(String... args) {
        String[] full = new String[args.length + 1];
        full[0] = "verify";
        System.arraycopy(args, 0, full, 1, args.length);
        return CliRunner.invoke(full);
    }

    @Test
    void anEmptyDirectoryHasNoBatches() {
        assertThat(Replay.findBatches(stick)).isEmpty();
    }

    @Test
    void aDirectoryThatIsNotOneIsRefused() throws IOException {
        Path target = tmp.resolve("file.json");
        Files.writeString(target, "{}", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> Replay.findBatches(target))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void batchesAreFoundWithTheirSignatures() {
        writeBatches(key, 2);
        List<BatchFile> found = Replay.findBatches(stick);
        assertThat(found).hasSize(2).allMatch(BatchFile::signed);
    }

    @Test
    void anUnsignedBatchIsFoundWithoutOne() {
        writeBatches(null, 2);
        List<BatchFile> found = Replay.findBatches(stick);
        assertThat(found).hasSize(2).noneMatch(BatchFile::signed);
    }

    @Test
    void filesThatAreNotBatchesAreIgnored() throws IOException {
        writeBatches(key, 1);
        write("notes.txt", "hello");
        Files.createDirectories(stick.resolve("inner"));
        assertThat(Replay.findBatches(stick)).hasSize(1);
    }

    @Test
    void aPayloadReadsBack() {
        writeBatches(key, 1);
        Map<String, Object> payload =
                Replay.readPayload(Replay.findBatches(stick).get(0).path());
        assertThat(payload).containsEntry("site", SITE);
        assertThat((List<?>) payload.get("records")).hasSize(2);
    }

    @Test
    void aFileThatIsNotJsonIsRefused() throws IOException {
        Path target = write("broken.json", "not json at all");
        assertThatThrownBy(() -> Replay.readPayload(target))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("not json");
    }

    @Test
    void aJsonFileThatIsNotAnObjectIsRefused() throws IOException {
        Path target = write("list.json", "[1, 2, 3]");
        assertThatThrownBy(() -> Replay.readPayload(target))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("not an object");
    }

    @ParameterizedTest
    @ValueSource(strings = {"site", "batch_id", "records"})
    void aBatchMissingAFieldIsRefused(String missing) throws IOException {
        Map<String, Object> payload = Maps.of("site", SITE, "batch_id", "abcd", "records", List.of());
        payload.remove(missing);
        Path target = write("short.json", Json.dumps(payload));
        assertThatThrownBy(() -> Replay.readPayload(target))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("no " + missing);
    }

    @Test
    void recordsThatAreNotAListAreRefused() throws IOException {
        Path target = write("odd.json", Json.dumps(Maps.of("site", SITE, "batch_id", "a", "records", Map.of())));
        assertThatThrownBy(() -> Replay.readPayload(target))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("not a list");
    }

    @Test
    void anHonestBatchVerifies() {
        writeBatches(key, 1);
        BatchVerdict verdict = Replay.checkOne(Replay.findBatches(stick).get(0), key, NOW);
        assertThat(verdict.ok()).isTrue();
        assertThat(verdict.records()).isEqualTo(2);
        assertThat(verdict.site()).isEqualTo(SITE);
    }

    @Test
    void aVerdictReadsAsOneLine() {
        writeBatches(key, 1);
        assertThat(Replay.checkOne(Replay.findBatches(stick).get(0), key, NOW).toString())
                .contains("ok, 2 records");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTamperedBatchDoesNotVerify() throws IOException {
        writeBatches(key, 1);
        BatchFile found = Replay.findBatches(stick).get(0);
        Map<String, Object> payload = Replay.readPayload(found.path());
        ((List<Map<String, Object>>) payload.get("records")).get(0).put("message", "something else entirely");
        Files.writeString(found.path(), Json.dumps(payload, Json.Style.compact()), StandardCharsets.UTF_8);
        BatchVerdict verdict = Replay.checkOne(found, key, NOW);
        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.reason()).contains("does not match");
    }

    @Test
    void aBatchSignedByAnotherKeyDoesNotVerify() {
        SigningKey other = new SigningKey(
                "tx-9999-2025", "z".repeat(SigningKey.MIN_SECRET_BYTES).getBytes(StandardCharsets.UTF_8));
        writeBatches(other, 1);
        BatchVerdict verdict = Replay.checkOne(Replay.findBatches(stick).get(0), key, NOW);
        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.reason()).contains("another key");
    }

    @Test
    void aBatchWithNoSignatureIsRefused() throws IOException {
        writeBatches(key, 1);
        Files.delete(Replay.findBatches(stick).get(0).signaturePath().orElseThrow());
        BatchVerdict verdict = Replay.checkOne(Replay.findBatches(stick).get(0), key, NOW);
        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.reason()).contains("no signature");
    }

    @Test
    void aSignatureThatIsNotOneIsRefused() throws IOException {
        writeBatches(key, 1);
        Files.writeString(
                Replay.findBatches(stick).get(0).signaturePath().orElseThrow(), "who knows", StandardCharsets.UTF_8);
        assertThat(Replay.checkOne(Replay.findBatches(stick).get(0), key, NOW).ok())
                .isFalse();
    }

    @Test
    void withoutAKeyNothingIsTakenAsVerified() {
        writeBatches(key, 1);
        BatchVerdict verdict = Replay.checkOne(Replay.findBatches(stick).get(0), null, NOW);
        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.reason()).contains("no key");
    }

    @Test
    void unsignedBatchesCanBeAcceptedDeliberately() {
        writeBatches(null, 1);
        BatchVerdict verdict = Replay.checkOne(Replay.findBatches(stick).get(0), null, NOW, false);
        assertThat(verdict.ok()).isTrue();
        assertThat(verdict.records()).isEqualTo(2);
    }

    @Test
    void aWholeStickVerifies() {
        writeBatches(key, 4);
        ReplayReport report = Replay.replay(stick, key, NOW);
        assertThat(report.clean()).isTrue();
        assertThat(report.accepted()).hasSize(4);
        assertThat(report.records()).isEqualTo(8);
    }

    @Test
    void theReportNamesTheSites() {
        writeBatches(key, 2);
        assertThat(Replay.replay(stick, key, NOW).sites()).containsExactly(SITE);
    }

    @Test
    void theReportReadsAsOneLine() {
        writeBatches(key, 2);
        assertThat(Replay.replay(stick, key, NOW).toString()).contains("2 batches accepted, 4 records from 1 sites");
    }

    @Test
    void oneBadFileDoesNotStopTheRest() throws IOException {
        writeBatches(key, 3);
        write("broken.json", "not json");
        ReplayReport report = Replay.replay(stick, key, NOW);
        assertThat(report.accepted()).hasSize(3);
        assertThat(report.refused()).hasSize(1);
        assertThat(report.clean()).isFalse();
    }

    @Test
    void theVerdictsComeBackInAStableOrder() {
        writeBatches(key, 5);
        List<String> first = Replay.replay(stick, key, NOW).verdicts().stream()
                .map(BatchVerdict::batchId)
                .toList();
        List<String> second = Replay.replay(stick, key, NOW).verdicts().stream()
                .map(BatchVerdict::batchId)
                .toList();
        assertThat(first).isEqualTo(second).isSorted();
    }

    @Test
    void anEmptyReportIsNotClean() {
        assertThat(new ReplayReport().clean()).isFalse();
    }

    @Test
    void theRecordsCanBePulledOut() {
        writeBatches(key, 3);
        List<Path> paths = Replay.replay(stick, key, NOW).accepted().stream()
                .map(BatchVerdict::path)
                .toList();
        assertThat(Replay.recordsOf(paths)).hasSize(6);
    }

    @Test
    void pullingRecordsKeepsDuplicates() {
        // Delivery is at least once and deduplicating is the collector's job.
        writeBatches(key, 1);
        List<Path> paths = Replay.replay(stick, key, NOW).accepted().stream()
                .map(BatchVerdict::path)
                .toList();
        List<Path> twice = new ArrayList<>(paths);
        twice.addAll(paths);
        assertThat(Replay.recordsOf(twice)).hasSize(4);
    }

    @Test
    void theCommandVerifiesAStick() throws IOException {
        writeBatches(key, 2, true);
        CliRunner.Result result = invoke(stick.toString(), "--key", keyFile().toString(), "--key-id", "tx-0417-2025");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("2 batches accepted");
    }

    @Test
    void theCommandRefusesATamperedStick() throws IOException {
        writeBatches(key, 2, true);
        Files.writeString(
                Replay.findBatches(stick).get(0).path(),
                "{\"site\": \"x\", \"batch_id\": \"y\", \"records\": []}",
                StandardCharsets.UTF_8);
        CliRunner.Result result = invoke(stick.toString(), "--key", keyFile().toString(), "--key-id", "tx-0417-2025");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("REFUSED");
    }

    @Test
    void aKeyWithoutAnIdIsRefused() throws IOException {
        assertThat(invoke(stick.toString(), "--key", keyFile().toString()).output())
                .contains("needs its key id");
    }

    @Test
    void noKeyAndNoFlagIsRefused() {
        assertThat(invoke(stick.toString()).output()).contains("or --unsigned");
    }

    @Test
    void theCommandSaysWhenNothingWasVerified() {
        writeBatches(null, 1);
        assertThat(invoke(stick.toString(), "--unsigned").output()).contains("nothing was verified");
    }

    @Test
    void anEmptyStickSaysSo() {
        assertThat(invoke(stick.toString(), "--unsigned").output()).contains("no batch files");
    }

    @Test
    void quietPrintsOnlyTheRefused() throws IOException {
        writeBatches(key, 2, true);
        write("broken.json", "not json");
        CliRunner.Result result =
                invoke(stick.toString(), "--key", keyFile().toString(), "--key-id", "tx-0417-2025", "--quiet");
        assertThat(result.output()).contains("broken.json").doesNotContain("ok, 2 records");
    }

    @Test
    void aDirectoryThatIsNotThereIsAUsageError() {
        CliRunner.Result result = invoke(tmp.resolve("absent").toString(), "--unsigned");
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("does not exist");
    }
}
