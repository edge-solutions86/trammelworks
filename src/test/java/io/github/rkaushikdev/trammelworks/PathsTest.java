package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PathsTest {

    static final String CID = "3f".repeat(32);
    static final String POD_DIR = "/var/log/pods/forecourt_pump-bridge-0_1f2e3d4c-aaaa-bbbb-cccc-1234567890ab";
    static final String CRI_PATH = POD_DIR + "/pump-bridge/0.log";
    static final String FLAT_PATH = "/var/log/containers/pump-bridge-0_forecourt_pump-bridge-" + CID + ".log";
    static final String DOCKER_PATH = "/var/lib/docker/containers/" + CID + "/" + CID + "-json.log";

    @Test
    void criPathGivesUnitAndAttempt() {
        LogPathInfo info = LogPaths.parseLogPath(CRI_PATH);
        assertThat(info.layout()).isSameAs(Layout.CRI);
        assertThat(info.unit()).isEqualTo("pump-bridge");
        assertThat(info.attempt()).contains(0);
        assertThat(info.namespace()).contains("forecourt");
        assertThat(info.pod()).contains("pump-bridge-0");
    }

    @Test
    void criAttemptFollowsTheRestartCount() {
        assertThat(LogPaths.parseLogPath(POD_DIR + "/pump-bridge/14.log").attempt())
                .contains(14);
    }

    @Test
    void criPathWithoutAPodDirectoryIsRefused() {
        assertThatThrownBy(() -> LogPaths.parseLogPath("/tmp/pump-bridge/0.log"))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("no pod directory");
    }

    @Test
    void criRotationIsReadFromTheSuffix() {
        assertThat(LogPaths.parseLogPath(POD_DIR + "/pump-bridge/0.log.3").rotation())
                .isEqualTo(3);
    }

    @Test
    void criCompressedRotationIsRecognised() {
        LogPathInfo info = LogPaths.parseLogPath(POD_DIR + "/pump-bridge/0.log.3.gz");
        assertThat(info.compressed()).isTrue();
        assertThat(info.rotation()).isEqualTo(3);
        assertThat(info.attempt()).contains(0);
    }

    @Test
    void flatPathGivesUnitAndContainerId() {
        LogPathInfo info = LogPaths.parseLogPath(FLAT_PATH);
        assertThat(info.layout()).isSameAs(Layout.FLAT);
        assertThat(info.unit()).isEqualTo("pump-bridge");
        assertThat(info.containerId()).contains(CID);
        assertThat(info.pod()).contains("pump-bridge-0");
        assertThat(info.namespace()).contains("forecourt");
    }

    @Test
    void flatPathHasNoAttempt() {
        assertThat(LogPaths.parseLogPath(FLAT_PATH).attempt()).isEmpty();
    }

    @Test
    void flatPathContainerNameMayContainDashes() {
        String path = "/var/log/containers/x_forecourt_tank-gauge-poller-" + CID + ".log";
        assertThat(LogPaths.parseLogPath(path).unit()).isEqualTo("tank-gauge-poller");
    }

    @Test
    void flatPathNeedsAFullLengthId() {
        assertThatThrownBy(() -> LogPaths.parseLogPath("/var/log/containers/p_ns_pump-bridge-3f3f3f.log"))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("unrecognised");
    }

    @Test
    void dockerPathNamesTheUnitAfterTheShortId() {
        LogPathInfo info = LogPaths.parseLogPath(DOCKER_PATH);
        assertThat(info.layout()).isSameAs(Layout.DOCKER);
        assertThat(info.containerId()).contains(CID);
        assertThat(info.unit()).isEqualTo("c3f3f3f3f3f3f");
    }

    @Test
    void dockerPathMustSitInItsOwnDirectory() {
        assertThatThrownBy(() -> LogPaths.parseLogPath("/var/lib/docker/containers/other/" + CID + "-json.log"))
                .isInstanceOf(ParseError.class)
                .hasMessageContaining("own container directory");
    }

    @Test
    void dockerRotationIsRead() {
        LogPathInfo info = LogPaths.parseLogPath("/var/lib/docker/containers/" + CID + "/" + CID + "-json.log.2");
        assertThat(info.rotation()).isEqualTo(2);
        assertThat(info.isCurrent()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/var/log/messages",
                "/var/log/containers/nothing.log",
                "/var/log/pods/ns_pod_uid/container/notanumber.log",
                "",
                "/var/lib/docker/containers/abc/abc-json.txt"
            })
    void unrecognisedPathsAreRefused(String path) {
        assertThatThrownBy(() -> LogPaths.parseLogPath(path)).isInstanceOf(ParseError.class);
    }

    @Test
    void theErrorNamesThePathItLookedAt() {
        ParseError caught = catchThrowableOfType(ParseError.class, () -> LogPaths.parseLogPath("/var/log/messages"));
        assertThat(caught.source()).contains("/var/log/messages");
    }

    @Test
    void isCurrentIsTrueOnlyForTheLiveFile() {
        assertThat(LogPaths.parseLogPath(CRI_PATH).isCurrent()).isTrue();
        assertThat(LogPaths.parseLogPath(POD_DIR + "/pump-bridge/0.log.1").isCurrent())
                .isFalse();
        assertThat(LogPaths.parseLogPath(POD_DIR + "/pump-bridge/0.log.gz").isCurrent())
                .isFalse();
    }

    @Test
    void rotationOrderReadsOldestFirst() {
        List<LogPathInfo> files = new ArrayList<>(List.of(
                LogPaths.parseLogPath(POD_DIR + "/pump-bridge/0.log"),
                LogPaths.parseLogPath(POD_DIR + "/pump-bridge/0.log.1"),
                LogPaths.parseLogPath(POD_DIR + "/pump-bridge/0.log.2")));
        files.sort(LogPaths.ROTATION_ORDER);
        assertThat(files).extracting(LogPathInfo::rotation).containsExactly(2, 1, 0);
    }

    @Test
    void rotationOrderPutsACompressedFileBeforeItsPlainTwin() {
        LogPathInfo plain = LogPaths.parseLogPath(POD_DIR + "/pump-bridge/0.log.1");
        LogPathInfo packed = LogPaths.parseLogPath(POD_DIR + "/pump-bridge/0.log.1.gz");
        List<LogPathInfo> files = new ArrayList<>(List.of(plain, packed));
        files.sort(LogPaths.ROTATION_ORDER);
        assertThat(files).containsExactly(packed, plain);
    }

    @Test
    void zstdIsAlsoTreatedAsCompressed() {
        assertThat(LogPaths.parseLogPath(POD_DIR + "/pump-bridge/0.log.1.zst").compressed())
                .isTrue();
    }

    @Test
    void parseAcceptsAPurepath() {
        assertThat(LogPaths.parseLogPath(Path.of(CRI_PATH)).unit()).isEqualTo("pump-bridge");
    }
}
