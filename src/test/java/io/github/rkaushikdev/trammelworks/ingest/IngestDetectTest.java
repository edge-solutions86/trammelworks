package io.github.rkaushikdev.trammelworks.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Layout;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.ParseError;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IngestDetectTest {

    static final String TS = "2025-03-04T11:30:00.123456789Z";
    static final String CID = "3f".repeat(32);
    static final String POD_DIR = "/var/log/pods/forecourt_pump-bridge-0_1f2e3d4c-aaaa-bbbb-cccc-1234567890ab";
    static final String CRI_PATH = POD_DIR + "/pump-bridge/0.log";
    static final String DOCKER_PATH = "/var/lib/docker/containers/" + CID + "/" + CID + "-json.log";
    static final byte[] CRI_SAMPLE = bytes(TS + " stdout F auth approved\n");
    static final byte[] JSON_SAMPLE =
            bytes(Json.dumps(Maps.of("log", "auth approved\n", "stream", "stdout", "time", TS)) + "\n");
    static final byte[] JOURNAL_SAMPLE = bytes("__REALTIME_TIMESTAMP=1741087800123456\nMESSAGE=auth approved\n\n");

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void firstLineStopsAtTheNewline() {
        assertThat(Detect.firstLine(bytes("one\ntwo\n"))).isEqualTo(bytes("one"));
    }

    @Test
    void firstLineOfAnUnterminatedSampleIsTheWholeThing() {
        assertThat(Detect.firstLine(bytes("only"))).isEqualTo(bytes("only"));
    }

    @Test
    void sniffFindsCri() {
        assertThat(Detect.sniff(CRI_SAMPLE)).contains(Format.CRI);
    }

    @Test
    void sniffFindsCriWithoutAFraction() {
        assertThat(Detect.sniff(bytes("2025-03-04T11:30:00Z stderr P chunk\n"))).contains(Format.CRI);
    }

    @Test
    void sniffFindsCriWithAnOffsetTimestamp() {
        assertThat(Detect.sniff(bytes("2025-03-04T05:30:00.1-06:00 stdout F x\n")))
                .contains(Format.CRI);
    }

    @Test
    void sniffFindsJsonfile() {
        assertThat(Detect.sniff(JSON_SAMPLE)).contains(Format.JSONFILE);
    }

    @Test
    void sniffFindsJournal() {
        assertThat(Detect.sniff(JOURNAL_SAMPLE)).contains(Format.JOURNAL);
    }

    @Test
    void sniffReturnsNoneForAnEmptySample() {
        assertThat(Detect.sniff(new byte[0])).isEmpty();
        assertThat(Detect.sniff(bytes("\n\n"))).isEmpty();
    }

    @Test
    void sniffReturnsNoneForProse() {
        assertThat(Detect.sniff(bytes("something went wrong at 11:30\n"))).isEmpty();
    }

    @Test
    void jsonThatIsNotALogRecordIsNotJsonfile() {
        assertThat(Detect.sniff(bytes("{\"hello\": \"world\"}\n"))).isEmpty();
    }

    @Test
    void brokenJsonIsNotJsonfile() {
        assertThat(Detect.sniff(bytes("{\"log\": \"x\", \"time\":\n"))).isEmpty();
    }

    @Test
    void aJsonArrayIsNotJsonfile() {
        assertThat(Detect.sniff(bytes("[{\"log\": \"x\", \"time\": \"y\"}]\n"))).isEmpty();
    }

    @Test
    void detectReadsACriFileAtACriPath() {
        Detection found = Detect.detect(CRI_PATH, CRI_SAMPLE);
        assertThat(found.format()).isSameAs(Format.CRI);
        assertThat(found.fromContent()).isTrue();
        assertThat(found.pathLayout()).contains(Layout.CRI);
        assertThat(found.disagreed()).isFalse();
    }

    @Test
    void detectReadsAJsonFileAtADockerPath() {
        Detection found = Detect.detect(DOCKER_PATH, JSON_SAMPLE);
        assertThat(found.format()).isSameAs(Format.JSONFILE);
        assertThat(found.disagreed()).isFalse();
    }

    @Test
    void contentWinsWhenAMigratedBoxLeftCriInADockerTree() {
        Detection found = Detect.detect(DOCKER_PATH, CRI_SAMPLE);
        assertThat(found.format()).isSameAs(Format.CRI);
        assertThat(found.fromContent()).isTrue();
        assertThat(found.disagreed()).isTrue();
    }

    @Test
    void thePathDecidesAnEmptyFile() {
        Detection found = Detect.detect(CRI_PATH, new byte[0]);
        assertThat(found.format()).isSameAs(Format.CRI);
        assertThat(found.fromContent()).isFalse();
    }

    @Test
    void anEmptyFileAtADockerPathIsJsonfile() {
        assertThat(Detect.detect(DOCKER_PATH, new byte[0]).format()).isSameAs(Format.JSONFILE);
    }

    @Test
    void anEmptyFileNeverCountsAsADisagreement() {
        assertThat(Detect.detect(DOCKER_PATH, new byte[0]).disagreed()).isFalse();
    }

    @Test
    void aFlatSymlinkPathExpectsCri() {
        String path = "/var/log/containers/pump-bridge-0_forecourt_pump-bridge-" + CID + ".log";
        assertThat(Detect.detect(path, new byte[0]).format()).isSameAs(Format.CRI);
    }

    @Test
    void anUnknownPathWithUnreadableContentIsRefused() {
        assertThatThrownBy(() -> Detect.detect("/var/log/messages", bytes("just some prose\n")))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("cannot tell what format");
    }

    @Test
    void theRefusalNamesThePath() {
        ParseError caught =
                catchThrowableOfType(ParseError.class, () -> Detect.detect("/var/log/messages", bytes("prose\n")));
        assertThat(caught.source()).contains("/var/log/messages");
    }

    @Test
    void anUnknownPathWithReadableContentIsFine() {
        Detection found = Detect.detect("/var/log/messages", CRI_SAMPLE);
        assertThat(found.format()).isSameAs(Format.CRI);
        assertThat(found.pathLayout()).isEmpty();
        assertThat(found.disagreed()).isFalse();
    }
}
