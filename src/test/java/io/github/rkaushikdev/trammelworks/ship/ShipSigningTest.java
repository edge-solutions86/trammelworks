package io.github.rkaushikdev.trammelworks.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.ManualClock;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.ShipError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShipSigningTest {

    static final byte[] SECRET = "k".repeat(SigningKey.MIN_SECRET_BYTES).getBytes(StandardCharsets.US_ASCII);
    static final NanoTime NOW = NanoTime.parse("2025-04-11T08:30:00Z");
    static final String SITE = "US-TX-0417";
    static final long SECOND = NanoTime.NS_PER_SECOND;

    @TempDir
    Path tmp;

    final SigningKey key = new SigningKey("tx-0417-2025", SECRET);
    final Batch batch = new Batch(SITE, List.of(Maps.of("message", "nozzle 1 lifted")), "seg-1");

    static Signature signed(SigningKey key, Batch batch) {
        return signed(key, batch, NOW);
    }

    static Signature signed(SigningKey key, Batch batch, NanoTime at) {
        return Signing.sign(key, batch.site(), batch.batchId(), batch.encode(), at);
    }

    Check verify(Signature signature, String site, String batchId, byte[] body) {
        return Signing.verify(key, signature, site, batchId, body, NOW);
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int index = 0; index <= haystack.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    void aKeyIdWithSpacesIsRefused() {
        assertThatThrownBy(() -> new SigningKey("tx 0417", SECRET))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("malformed signing key id");
    }

    @Test
    void anEmptyKeyIdIsRefused() {
        assertThatThrownBy(() -> new SigningKey("", SECRET))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("malformed signing key id");
    }

    @Test
    void aShortSecretIsRefused() {
        assertThatThrownBy(() -> new SigningKey("tx-0417", bytes("short")))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("too short");
    }

    @Test
    void anEnormousSecretIsRefused() {
        assertThatThrownBy(() -> new SigningKey("tx-0417", bytes("k".repeat(5000))))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("implausibly long");
    }

    @Test
    void theSecretNeverAppearsInTheRepr() {
        assertThat(key.toString()).doesNotContain("kkkk").contains("32 bytes");
    }

    @Test
    void aKeyIsReadOffDisk() throws IOException {
        Path path = Files.write(tmp.resolve("site.key"), SECRET);
        assertThat(SigningKey.read(path, "tx-0417").secret()).isEqualTo(SECRET);
    }

    @Test
    void aTrailingNewlineIsNotPartOfTheKey() throws IOException {
        byte[] withNewline = Arrays.copyOf(SECRET, SECRET.length + 1);
        withNewline[SECRET.length] = '\n';
        Path path = Files.write(tmp.resolve("site.key"), withNewline);
        assertThat(SigningKey.read(path, "tx-0417").secret()).isEqualTo(SECRET);
    }

    @Test
    void anEmptyKeyFileIsRefused() throws IOException {
        Path path = Files.write(tmp.resolve("site.key"), bytes("   \n"));
        assertThatThrownBy(() -> SigningKey.read(path, "tx-0417"))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("empty");
    }

    @Test
    void aMissingKeyFileIsRefused() {
        assertThatThrownBy(() -> SigningKey.read(tmp.resolve("absent.key"), "tx-0417"))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("unreadable");
    }

    @Test
    void theSignedStringCarriesEveryField() {
        byte[] material = Signing.signingString("tx-0417", "2025-04-11T08:30:00Z", SITE, "abcd", bytes("{}"));
        assertThat(new String(material, StandardCharsets.UTF_8)).startsWith(Signing.SIGNATURE_VERSION);
        for (String part : List.of("tx-0417", "2025-04-11T08:30:00Z", SITE, "abcd", "{}")) {
            assertThat(contains(material, bytes(part))).isTrue();
        }
    }

    @Test
    void aNewlineInASignedFieldIsRefused() {
        assertThatThrownBy(() -> Signing.signingString("tx-0417", "2025\n04", SITE, "abcd", bytes("{}")))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("cannot contain a newline");
    }

    @Test
    void theSameBatchSignsTheSameWay() {
        assertThat(signed(key, batch).digest()).isEqualTo(signed(key, batch).digest());
    }

    @Test
    void aDifferentKeySignsDifferently() {
        SigningKey other = new SigningKey("tx-0417-2025", bytes("z".repeat(SigningKey.MIN_SECRET_BYTES)));
        assertThat(signed(key, batch).digest())
                .isNotEqualTo(signed(other, batch).digest());
    }

    @Test
    void aDifferentTimeSignsDifferently() {
        assertThat(signed(key, batch).digest())
                .isNotEqualTo(signed(key, batch, NOW.shifted(SECOND)).digest());
    }

    @Test
    void theSignatureMatchesTheDigestTheCollectorComputes() {
        // Pinned against the reference implementation, so the collector accepts a Java site's batches.
        assertThat(Signing.sign(key, SITE, "abcd", bytes("{}"), NOW).digest()).isEqualTo(REFERENCE_DIGEST);
    }

    static final String REFERENCE_DIGEST = "b18c8757bbb5e7243535abef8c1d6f95d5139ba6b118e22f4e1630a683c09ec7";

    @Test
    void aSignatureReadsBackFromItsHeader() {
        Signature original = signed(key, batch);
        assertThat(Signing.parse(original.header())).isEqualTo(original);
    }

    @Test
    void aHeaderThatIsNotOneIsRefused() {
        assertThatThrownBy(() -> Signing.parse("who knows"))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void aHeaderWithAShortDigestIsRefused() {
        String header = signed(key, batch).header();
        assertThatThrownBy(() -> Signing.parse(header.substring(0, header.length() - 4)))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void anHonestSignatureVerifies() {
        Check check = verify(signed(key, batch), batch.site(), batch.batchId(), batch.encode());
        assertThat(check.ok()).isTrue();
        assertThat(check.reason()).isEmpty();
    }

    @Test
    void aVerifiedCheckIsTruthy() {
        assertThat(verify(signed(key, batch), batch.site(), batch.batchId(), batch.encode())
                        .ok())
                .isTrue();
    }

    @Test
    void aChangedBodyDoesNotVerify() {
        assertThat(verify(signed(key, batch), batch.site(), batch.batchId(), bytes("{}"))
                        .reason())
                .isEqualTo("signature does not match");
    }

    @Test
    void aBatchReplayedAsAnotherSiteDoesNotVerify() {
        assertThat(verify(signed(key, batch), "US-TX-9999", batch.batchId(), batch.encode())
                        .ok())
                .isFalse();
    }

    @Test
    void aBatchReplayedUnderAnotherIdDoesNotVerify() {
        assertThat(verify(signed(key, batch), batch.site(), "beefbeef", batch.encode())
                        .ok())
                .isFalse();
    }

    @Test
    void anotherKeyIsNamedNotJustRefused() {
        SigningKey other = new SigningKey("tx-9999-2025", SECRET);
        assertThat(verify(signed(other, batch), batch.site(), batch.batchId(), batch.encode())
                        .reason())
                .contains("another key");
    }

    @Test
    void anotherVersionIsRefused() {
        Signature stale = new Signature(key.keyId(), "2025-04-11T08:30:00Z", "0".repeat(64), "stanchion-batch-v0");
        assertThat(verify(stale, batch.site(), batch.batchId(), batch.encode()).reason())
                .contains("another version");
    }

    @Test
    void anUnreadableTimestampIsRefused() {
        Signature broken = new Signature(key.keyId(), "last tuesday", "0".repeat(64));
        assertThat(verify(broken, batch.site(), batch.batchId(), batch.encode()).reason())
                .contains("unreadable");
    }

    @Test
    void aSignatureFromLongAgoIsRefused() {
        NanoTime old = NOW.shifted(-((long) Signing.DEFAULT_SKEW_SECONDS + 60) * SECOND);
        assertThat(verify(signed(key, batch, old), batch.site(), batch.batchId(), batch.encode())
                        .reason())
                .contains("seconds away from now");
    }

    @Test
    void aSignatureInsideTheWindowStillVerifies() {
        NanoTime old = NOW.shifted(-((long) Signing.DEFAULT_SKEW_SECONDS - 60) * SECOND);
        assertThat(verify(signed(key, batch, old), batch.site(), batch.batchId(), batch.encode())
                        .ok())
                .isTrue();
    }

    @Test
    void aWindowCanBeNarrowed() {
        NanoTime old = NOW.shifted(-3600 * SECOND);
        Check check =
                Signing.verify(key, signed(key, batch, old), batch.site(), batch.batchId(), batch.encode(), NOW, 60);
        assertThat(check.ok()).isFalse();
    }

    static Signer signerFor(SigningKey key) {
        ManualClock clock = new ManualClock();
        clock.setWall("2025-04-11T08:30:00Z");
        return new Signer(key, clock);
    }

    @Test
    void aSignerCountsWhatItSigned() {
        Signer signer = signerFor(key);
        signer.forBatch(batch, batch.encode());
        signer.forBatch(batch, batch.encode());
        assertThat(signer.signed()).isEqualTo(2);
    }

    @Test
    void aSignerProducesAHeaderThatVerifies() {
        Signature found = Signing.parse(signerFor(key).headerFor(batch, batch.encode()));
        assertThat(verify(found, batch.site(), batch.batchId(), batch.encode()).ok())
                .isTrue();
    }

    @Test
    void aFileTransportWritesTheSignatureBesideTheBatch() {
        new FileTransport(tmp, signerFor(key)).send(batch);
        assertThat(tmp.resolve(batch.batchId() + ".json" + Transport.SIGNATURE_SUFFIX))
                .exists();
    }

    @Test
    void theSignatureBesideTheBatchVerifies() throws IOException {
        new FileTransport(tmp, signerFor(key)).send(batch);
        byte[] body = Files.readAllBytes(tmp.resolve(batch.batchId() + ".json"));
        String header = Files.readString(tmp.resolve(batch.batchId() + ".json" + Transport.SIGNATURE_SUFFIX));
        assertThat(verify(Signing.parse(header), batch.site(), batch.batchId(), body)
                        .ok())
                .isTrue();
    }

    @Test
    void anUnsignedFileTransportWritesNoSidecar() throws IOException {
        new FileTransport(tmp).send(batch);
        try (Stream<Path> found = Files.list(tmp)) {
            assertThat(found.filter(path -> path.toString().endsWith(Transport.SIGNATURE_SUFFIX)))
                    .isEmpty();
        }
    }

    static HttpTransport.Opener recorder(AtomicReference<Map<String, String>> seen) {
        return (request, timeout) -> {
            seen.set(request.headers());
            return new HttpTransport.Answer(200);
        };
    }

    @Test
    void anHttpTransportSendsTheSignatureHeader() {
        AtomicReference<Map<String, String>> seen = new AtomicReference<>(Map.of());
        new HttpTransport(
                        "https://collector.invalid/v1",
                        HttpTransport.DEFAULT_TIMEOUT,
                        "",
                        signerFor(key),
                        recorder(seen))
                .send(batch);
        assertThat(seen.get().keySet()).anyMatch(name -> name.equalsIgnoreCase(Transport.SIGNATURE_HEADER));
    }

    @Test
    void theHeaderItSendsVerifies() {
        AtomicReference<Map<String, String>> seen = new AtomicReference<>(Map.of());
        new HttpTransport(
                        "https://collector.invalid/v1",
                        HttpTransport.DEFAULT_TIMEOUT,
                        "",
                        signerFor(key),
                        recorder(seen))
                .send(batch);
        String header = seen.get().entrySet().stream()
                .filter(entry ->
                        entry.getKey().toLowerCase(java.util.Locale.ROOT).contains("signature"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        assertThat(verify(Signing.parse(header), batch.site(), batch.batchId(), batch.encode())
                        .ok())
                .isTrue();
    }

    @Test
    void anUnsignedHttpTransportSendsNoSuchHeader() {
        AtomicReference<Map<String, String>> seen = new AtomicReference<>(Map.of());
        HttpTransport.with("https://collector.invalid/v1", recorder(seen)).send(batch);
        assertThat(seen.get().keySet())
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("signature"));
    }

    @Test
    void aKeyFileWithoutAnIdIsRefused() {
        assertThatThrownBy(() -> ShipSettings.builder()
                        .signingKeyFile("/etc/stanchion/site.key")
                        .build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("both a key file and a key id");
    }

    @Test
    void aKeyIdWithoutAFileIsRefused() {
        assertThatThrownBy(() -> ShipSettings.builder().signingKeyId("tx-0417").build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("both a key file and a key id");
    }

    @Test
    void aSiteWithoutSigningHasNoKey() {
        assertThat(new ShipSettings().signingKey()).isEmpty();
        assertThat(new ShipSettings().signs()).isFalse();
    }

    @Test
    void aConfiguredKeyIsLoaded() throws IOException {
        Path path = Files.write(tmp.resolve("site.key"), SECRET);
        ShipSettings settings = ShipSettings.builder()
                .signingKeyFile(path.toString())
                .signingKeyId("tx-0417")
                .build();
        assertThat(settings.signingKey()).map(SigningKey::keyId).contains("tx-0417");
    }

    @Test
    void aConfiguredKeyThatIsNotThereIsAConfigError() {
        ShipSettings settings = ShipSettings.builder()
                .signingKeyFile(tmp.resolve("absent.key").toString())
                .signingKeyId("tx-0417")
                .build();
        assertThatThrownBy(settings::signingKey).isInstanceOf(ConfigError.class).hasMessageContaining("unreadable");
    }
}
