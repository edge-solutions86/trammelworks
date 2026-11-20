package io.github.rkaushikdev.trammelworks.spool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.Samples;
import io.github.rkaushikdev.trammelworks.SpoolError;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SpoolFrameTest {

    static final Map<String, Object> SMALL = Map.of("site", "US-TX-0417", "message", "nozzle 1 lifted");
    static final Map<String, Object> BIG = Map.of("site", "US-TX-0417", "message", "auth approved ".repeat(60));

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] handmade(byte[] body) {
        ByteBuffer out = ByteBuffer.allocate(Frames.OVERHEAD + body.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put(Frames.MAGIC).put((byte) 1).put((byte) 0).putInt(body.length).put(body);
        out.putInt((int) Frames.crc32(body, 0, body.length));
        return out.array();
    }

    @Test
    void aFrameStartsWithTheMagic() {
        assertThat(Arrays.copyOf(Frames.encodeFrame(SMALL), 2)).isEqualTo(Frames.MAGIC);
    }

    @Test
    void aFrameRoundTrips() {
        Frames.Decoded decoded = Frames.decodeFrame(Frames.encodeFrame(SMALL));
        assertThat(decoded.frame().payload()).isEqualTo(SMALL);
        assertThat(decoded.next()).isEqualTo(Frames.encodeFrame(SMALL).length);
    }

    @Test
    void aSmallPayloadIsLeftUncompressed() {
        byte[] encoded = Frames.encodeFrame(SMALL);
        assertThat(encoded[3] & Frames.FLAG_DEFLATE).isZero();
        assertThat(Frames.decodeFrame(encoded).frame().compressed()).isFalse();
    }

    @Test
    void aRepetitivePayloadIsCompressed() {
        byte[] encoded = Frames.encodeFrame(BIG);
        assertThat(encoded[3] & Frames.FLAG_DEFLATE).isEqualTo(Frames.FLAG_DEFLATE);
        assertThat(Frames.decodeFrame(encoded).frame().compressed()).isTrue();
    }

    @Test
    void compressionActuallyMakesItSmaller() {
        assertThat(Frames.encodeFrame(BIG).length).isLessThan(Frames.encodeFrame(BIG, false).length);
    }

    @Test
    void compressionCanBeTurnedOff() {
        assertThat(Frames.encodeFrame(BIG, false)[3] & Frames.FLAG_DEFLATE).isZero();
    }

    @Test
    void aCompressedFrameRoundTrips() {
        assertThat(Frames.decodeFrame(Frames.encodeFrame(BIG)).frame().payload())
                .isEqualTo(BIG);
    }

    @Test
    void theStoredSizeIsTheWholeFrame() {
        byte[] encoded = Frames.encodeFrame(SMALL);
        assertThat(Frames.decodeFrame(encoded).frame().storedSize()).isEqualTo(encoded.length);
    }

    @Test
    void theOverheadIsTwelveBytes() {
        assertThat(Frames.OVERHEAD).isEqualTo(12);
        byte[] encoded = Frames.encodeFrame(SMALL, false);
        int body = encoded.length - Frames.OVERHEAD;
        assertThat(ByteBuffer.wrap(encoded, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt())
                .isEqualTo(body);
    }

    @Test
    void aPayloadThatCannotBeEncodedIsRefused() {
        assertThatThrownBy(() -> Frames.encodeFrame(Map.of("bad", new Object())))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("cannot be encoded");
    }

    @Test
    void frameLengthReadsTheHeader() {
        byte[] encoded = Frames.encodeFrame(SMALL);
        assertThat(Frames.frameLength(encoded)).hasValue(encoded.length);
    }

    @Test
    void frameLengthOfAShortBufferIsNone() {
        assertThat(Frames.frameLength(Arrays.copyOf(Frames.encodeFrame(SMALL), 4)))
                .isEmpty();
    }

    @Test
    void frameLengthOfATruncatedBodyIsNone() {
        byte[] encoded = Frames.encodeFrame(SMALL);
        assertThat(Frames.frameLength(Arrays.copyOf(encoded, encoded.length - 2)))
                .isEmpty();
    }

    @Test
    void frameLengthRefusesAWrongMagic() {
        byte[] encoded = Frames.encodeFrame(SMALL);
        encoded[0] = 'X';
        encoded[1] = 'X';
        assertThatThrownBy(() -> Frames.frameLength(encoded))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("magic");
    }

    @Test
    void frameLengthRefusesAnImpossibleLength() {
        byte[] encoded = Frames.encodeFrame(SMALL);
        ByteBuffer.wrap(encoded, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(1 << 30);
        assertThatThrownBy(() -> Frames.frameLength(encoded))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("impossible length");
    }

    @Test
    void aTruncatedFrameIsRefused() {
        byte[] encoded = Frames.encodeFrame(SMALL);
        assertThatThrownBy(() -> Frames.decodeFrame(Arrays.copyOf(encoded, encoded.length - 1)))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void aFlippedPayloadByteIsCaught() {
        byte[] encoded = Frames.encodeFrame(SMALL, false);
        encoded[Frames.HEADER_SIZE] ^= (byte) 0xFF;
        assertThatThrownBy(() -> Frames.decodeFrame(encoded))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void aFlippedChecksumByteIsCaught() {
        byte[] encoded = Frames.encodeFrame(SMALL);
        encoded[encoded.length - 1] ^= (byte) 0xFF;
        assertThatThrownBy(() -> Frames.decodeFrame(encoded))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void aFrameFromAnotherFormatVersionIsRefused() {
        byte[] encoded = Frames.encodeFrame(SMALL);
        encoded[2] = 99;
        assertThatThrownBy(() -> Frames.decodeFrame(encoded))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("another spool format");
    }

    @Test
    void aPayloadThatIsNotJsonIsRefused() {
        byte[] encoded = handmade("not json at all".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> Frames.decodeFrame(encoded))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("not json");
    }

    @Test
    void aPayloadThatIsNotAnObjectIsRefused() {
        byte[] encoded = handmade("[1,2,3]".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> Frames.decodeFrame(encoded))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("not an object");
    }

    @Test
    void aFrameFlaggedDeflateThatIsNotIsRefused() {
        byte[] encoded = Frames.encodeFrame(SMALL, false);
        encoded[3] |= Frames.FLAG_DEFLATE;
        assertThatThrownBy(() -> Frames.decodeFrame(encoded))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("will not decompress");
    }

    @Test
    void twoFramesDecodeInOrder() {
        byte[] data = concat(Frames.encodeFrame(Map.of("n", 1)), Frames.encodeFrame(Map.of("n", 2)));
        Frames.Run run = Frames.iterFrames(data);
        assertThat(run.frames()).extracting(frame -> frame.payload().get("n")).containsExactly(1L, 2L);
        assertThat(run.consumed()).isEqualTo(data.length);
        assertThat(run.note()).isEmpty();
    }

    @Test
    void anEmptyBufferGivesNothing() {
        assertThat(Frames.iterFrames(new byte[0])).isEqualTo(new Frames.Run(List.of(), 0, java.util.Optional.empty()));
    }

    @Test
    void aTornTailStopsTheWalkWithoutLosingTheHead() {
        byte[] good = Frames.encodeFrame(Map.of("n", 1));
        byte[] data = concat(good, Arrays.copyOf(Frames.encodeFrame(Map.of("n", 2)), 6));
        Frames.Run run = Frames.iterFrames(data);
        assertThat(run.frames()).extracting(frame -> frame.payload().get("n")).containsExactly(1L);
        assertThat(run.consumed()).isEqualTo(good.length);
        assertThat(run.note()).isPresent();
    }

    @Test
    void aCorruptMiddleFrameStopsTheWalkThere() {
        byte[] first = Frames.encodeFrame(Map.of("n", 1), false);
        byte[] second = Frames.encodeFrame(Map.of("n", 2), false);
        second[Frames.HEADER_SIZE] ^= (byte) 0xFF;
        Frames.Run run = Frames.iterFrames(concat(first, second));
        assertThat(run.frames()).hasSize(1);
        assertThat(run.consumed()).isEqualTo(first.length);
        assertThat(run.note()).hasValueSatisfying(note -> assertThat(note).contains("checksum"));
    }

    @Test
    void theConsumedOffsetIsWhereATruncateShouldLand() {
        byte[] good = Frames.encodeFrame(Map.of("n", 1));
        byte[] data = concat(good, new byte[3]);
        int consumed = Frames.iterFrames(data).consumed();
        assertThat(Arrays.copyOf(data, consumed)).isEqualTo(good);
    }

    static final String ALPHABET = "abcdefghij KLMNOP 0123456789 éßø€中文\u0001\n\t\"\\😀";

    static Stream<Map<String, Object>> flatMappings() {
        return Stream.concat(Stream.of(Map.of()), Samples.drawn(29, random -> {
            Map<String, Object> out = new LinkedHashMap<>();
            int size = random.nextInt(7);
            for (int index = 0; index < size; index++) {
                out.put(Samples.text(random, ALPHABET, 1, 8), Samples.text(random, ALPHABET, 0, 40));
            }
            return out;
        }));
    }

    @ParameterizedTest
    @MethodSource("flatMappings")
    void anyFlatMappingRoundTrips(Map<String, Object> payload) {
        assertThat(Frames.decodeFrame(Frames.encodeFrame(payload)).frame().payload())
                .isEqualTo(payload);
    }

    /** A deflated frame as an earlier release wrote it to a site's spool. */
    static final String REFERENCE_FRAME =
            "53500101ae000000789ced91dd0ac2300c855f65e47a956e2ac25e43055164746dd4b12e1dfd5164ecdd4d2ff616865ce4e47ce4e264066d9d1e5aede8d11b248dd040a281dc87a004ab3ab4019a193c1aa5231ab62b584a183104f5ccb44af155a869f2ee8da6f8ab55717cae0be8796e55e4a06a59ef85dc0ab93b55752325f746ae75653cf431077a3e8ad385a9ea90772e798dfc82db9d45f4a8464642342e45b613f5f9f4a4be235214037a420bcb0f5a2fa55a449d831c";

    @Test
    void aFrameAnEarlierReleaseWroteStillReads() {
        // A site upgraded in place keeps its spool; the segments already on disk must decode.
        Frame frame = Frames.decodeFrame(java.util.HexFormat.of().parseHex(REFERENCE_FRAME))
                .frame();
        assertThat(frame.compressed()).isTrue();
        assertThat(frame.payload().get("message")).isEqualTo("auth approved ".repeat(20));
        assertThat(frame.payload().get("labels")).isEqualTo(Map.of("redacted", "1"));
    }
}
