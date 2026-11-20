package io.github.rkaushikdev.trammelworks.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.Layout;
import io.github.rkaushikdev.trammelworks.LogFiles;
import io.github.rkaushikdev.trammelworks.LogPathInfo;
import io.github.rkaushikdev.trammelworks.LogPaths;
import io.github.rkaushikdev.trammelworks.SiteTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestDiscoverTest {

    static final String TS = "2025-03-04T11:30:00.123456789Z";
    static final String CID = "3f".repeat(32);
    static final String CRI =
            "/var/log/pods/forecourt_pump-bridge-0_1f2e3d4c-aaaa-bbbb-cccc-1234567890ab/pump-bridge/0.log";
    static final String FLAT = "/var/log/containers/pump-bridge-0_forecourt_pump-bridge-" + CID + ".log";

    @TempDir
    Path tmp;

    SiteTree tree;

    @BeforeEach
    void setUp() {
        tree = new SiteTree(tmp.resolve("log"));
    }

    Path seed(String unit) {
        return seed(unit, 0);
    }

    Path seed(String unit, int attempt) {
        return tree.writeCri(unit, List.of(LogFiles.criFull(TS, unit + " is up")), attempt);
    }

    @Test
    void walkOfAnAbsentRootIsEmpty() {
        assertThat(Discover.walk(tmp.resolve("nope"))).isEmpty();
    }

    @Test
    void walkFindsFilesInAStableOrder() {
        seed("pump-bridge");
        seed("tank-gauge");
        List<Path> first = Discover.walk(tree.root());
        assertThat(first).isEqualTo(Discover.walk(tree.root()));
        assertThat(first).hasSize(2);
    }

    @Test
    void walkIgnoresDirectories() {
        seed("pump-bridge");
        assertThat(Discover.walk(tree.root())).allMatch(Files::isRegularFile);
    }

    @Test
    void discoverFollowsALiveFile() {
        seed("pump-bridge");
        Discovery found = Discover.discover(tree.root());
        assertThat(found.follow()).extracting(LogPathInfo::unit).containsExactly("pump-bridge");
        assertThat(found.skipped()).isEmpty();
    }

    @Test
    void discoverSortsUnits() {
        seed("tank-gauge");
        seed("pump-bridge");
        assertThat(Discover.discover(tree.root()).units()).containsExactly("pump-bridge", "tank-gauge");
    }

    @Test
    void aRotatedFileIsSkipped() {
        tree.rotate(seed("pump-bridge"));
        Discovery found = Discover.discover(tree.root());
        assertThat(found.reasons().get(SkipReason.ROTATED)).isEqualTo(1);
        assertThat(found.follow()).extracting(LogPathInfo::unit).containsExactly("pump-bridge");
    }

    @Test
    void aCompressedFileIsSkipped() throws IOException {
        Path path = seed("pump-bridge");
        Files.write(path.resolveSibling(path.getFileName() + ".1.gz"), "packed".getBytes());
        assertThat(Discover.discover(tree.root()).reasons().get(SkipReason.COMPRESSED))
                .isEqualTo(1);
    }

    @Test
    void anUnrelatedFileIsSkippedAsUnparseable() throws IOException {
        seed("pump-bridge");
        Files.writeString(tree.root().resolve("messages"), "nothing to do with us");
        assertThat(Discover.discover(tree.root()).reasons().get(SkipReason.UNPARSEABLE))
                .isEqualTo(1);
    }

    @Test
    void classifyReturnsCandidatesAndSkips() throws IOException {
        seed("pump-bridge");
        Files.writeString(tree.root().resolve("messages"), "x");
        Discover.Classified classified = Discover.classify(Discover.walk(tree.root()));
        assertThat(classified.candidates()).hasSize(1);
        assertThat(classified.skipped()).hasSize(1);
    }

    @Test
    void twoLayoutsOfOneUnitCollapseToThePreferredOne() {
        Discover.Deduplicated found =
                Discover.deduplicate(List.of(LogPaths.parseLogPath(FLAT), LogPaths.parseLogPath(CRI)));
        assertThat(found.kept()).extracting(LogPathInfo::layout).containsExactly(Layout.CRI);
        assertThat(found.losers().get(0).reason()).isSameAs(SkipReason.DUPLICATE);
    }

    @Test
    void thePreferenceHoldsWhicheverOrderTheyArriveIn() {
        LogPathInfo cri = LogPaths.parseLogPath(CRI);
        LogPathInfo flat = LogPaths.parseLogPath(FLAT);
        assertThat(Discover.deduplicate(List.of(cri, flat)).kept().get(0).layout())
                .isSameAs(Layout.CRI);
        assertThat(Discover.deduplicate(List.of(flat, cri)).kept().get(0).layout())
                .isSameAs(Layout.CRI);
    }

    @Test
    void theLaterRestartAttemptWinsInsideOneLayout() {
        seed("pump-bridge", 0);
        seed("pump-bridge", 3);
        Discovery found = Discover.discover(tree.root());
        assertThat(found.follow()).hasSize(1);
        assertThat(found.follow().get(0).attempt()).contains(3);
        assertThat(found.reasons().get(SkipReason.DUPLICATE)).isEqualTo(1);
    }

    @Test
    void twoDifferentUnitsAreBothKept() {
        seed("pump-bridge");
        seed("tank-gauge");
        assertThat(Discover.discover(tree.root()).follow()).hasSize(2);
    }

    @Test
    void theUnitFilterSelects() {
        seed("pump-bridge");
        seed("tank-gauge");
        Discovery found = Discover.discover(tree.root(), List.of("pump-bridge"));
        assertThat(found.follow()).extracting(LogPathInfo::unit).containsExactly("pump-bridge");
        assertThat(found.reasons().get(SkipReason.NOT_SELECTED)).isEqualTo(1);
    }

    @Test
    void anEmptyFilterSelectsEverything() {
        seed("pump-bridge");
        seed("tank-gauge");
        assertThat(Discover.discover(tree.root(), List.of()).follow()).hasSize(2);
    }

    @Test
    void aFilterNamingAnAbsentUnitFollowsNothing() {
        seed("pump-bridge");
        assertThat(Discover.discover(tree.root(), List.of("loyalty-sync")).follow())
                .isEmpty();
    }

    @Test
    void reasonsCountsEachKind() throws IOException {
        tree.rotate(seed("pump-bridge"));
        Files.writeString(tree.root().resolve("messages"), "x");
        var counts = Discover.discover(tree.root()).reasons();
        assertThat(counts.get(SkipReason.ROTATED)).isEqualTo(1);
        assertThat(counts.get(SkipReason.UNPARSEABLE)).isEqualTo(1);
    }

    @Test
    void reasonsOfACleanWalkIsEmpty() {
        seed("pump-bridge");
        assertThat(Discover.discover(tree.root()).reasons()).isEmpty();
    }

    @Test
    void discoveryOfAnEmptyRootFindsNothing() {
        Discovery found = Discover.discover(tmp);
        assertThat(found.follow()).isEmpty();
        assertThat(found.units()).isEmpty();
    }
}
