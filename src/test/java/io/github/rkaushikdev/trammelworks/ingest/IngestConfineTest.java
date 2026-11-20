package io.github.rkaushikdev.trammelworks.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.IngestError;
import io.github.rkaushikdev.trammelworks.LogFiles;
import io.github.rkaushikdev.trammelworks.LogPathInfo;
import io.github.rkaushikdev.trammelworks.SiteTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestConfineTest {

    static final String TS = "2025-03-04T12:00:00.123456789Z";

    @TempDir
    Path tmp;

    Path root;
    SiteTree tree;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tmp.resolve("containers"));
        tree = new SiteTree(tmp.resolve("log"));
    }

    static void link(Path target, Path link) throws IOException {
        Files.createSymbolicLink(link, target);
    }

    static void write(Path path, String text) throws IOException {
        Files.writeString(path, text);
    }

    Path real() throws IOException {
        return root.toRealPath();
    }

    @Test
    void aRootResolves() throws IOException {
        assertThat(Confinement.resolveRoot(root)).isEqualTo(real());
    }

    @Test
    void aRootThatIsNotThereStillResolves() {
        assertThat(Confinement.resolveRoot(tmp.resolve("absent")).getFileName()).hasToString("absent");
    }

    @Test
    void aPathUnderTheRootIsWithin() {
        assertThat(Confinement.within(root, root.resolve("a").resolve("b.log"))).isTrue();
    }

    @Test
    void theRootItselfIsWithin() {
        assertThat(Confinement.within(root, root)).isTrue();
    }

    @Test
    void aPathBesideTheRootIsNotWithin() {
        assertThat(Confinement.within(root, tmp.resolve("elsewhere").resolve("b.log")))
                .isFalse();
    }

    @Test
    void aPathWhoseNameStartsTheSameIsNotWithin() {
        // containers-old is not inside containers, however it sorts.
        assertThat(Confinement.within(
                        root, root.resolveSibling(root.getFileName() + "-old").resolve("b.log")))
                .isFalse();
    }

    @Test
    void anOrdinaryFileIsAllowed() throws IOException {
        Path target = root.resolve("0.log");
        write(target, "hello");
        assertThat(Confinement.check(target, real()).ok()).isTrue();
    }

    @Test
    void aMissingFileIsRefused() throws IOException {
        assertThat(Confinement.check(root.resolve("absent.log"), real()).refusal())
                .contains(Refusal.UNREADABLE);
    }

    @Test
    void aDirectoryIsNotAFile() throws IOException {
        Files.createDirectories(root.resolve("inner"));
        assertThat(Confinement.check(root.resolve("inner"), real()).refusal()).contains(Refusal.NOT_A_FILE);
    }

    @Test
    void aVerdictSaysWhatItDecided() throws IOException {
        Path target = root.resolve("0.log");
        write(target, "hi");
        assertThat(Confinement.check(target, real()).toString()).contains("ok");
        assertThat(Verdict.refused(target, Refusal.OUTSIDE).toString()).contains("refused");
    }

    @Test
    void aLinkInsideTheRootIsAllowed() throws IOException {
        Path realFile = root.resolve("docker").resolve("0.log");
        Files.createDirectories(realFile.getParent());
        write(realFile, "hello");
        Path link = root.resolve("cri.log");
        link(realFile, link);
        assertThat(Confinement.check(link, real()).ok()).isTrue();
    }

    @Test
    void aLinkOutOfTheRootIsRefused() throws IOException {
        Path outside = tmp.resolve("secrets");
        write(outside, "not a log");
        Path link = root.resolve("0.log");
        link(outside, link);
        Verdict verdict = Confinement.check(link, real());
        assertThat(verdict.refusal()).contains(Refusal.OUTSIDE);
        assertThat(verdict.resolved()).contains(outside.toRealPath());
    }

    @Test
    void aLinkToADirectoryOutOfTheRootIsRefused() throws IOException {
        Path outside = tmp.resolve("elsewhere");
        Files.createDirectories(outside.resolve("deep"));
        write(outside.resolve("deep").resolve("0.log"), "x");
        Path link = root.resolve("moved");
        link(outside, link);
        assertThat(Confinement.check(link.resolve("deep").resolve("0.log"), real())
                        .refusal())
                .contains(Refusal.OUTSIDE);
    }

    @Test
    void aBrokenLinkIsRefused() throws IOException {
        Path link = root.resolve("0.log");
        link(root.resolve("gone.log"), link);
        assertThat(Confinement.check(link, real()).refusal()).contains(Refusal.UNREADABLE);
    }

    @Test
    void aFifoIsRefused() throws IOException, InterruptedException {
        Path target = root.resolve("pipe.log");
        Process made = new ProcessBuilder("mkfifo", target.toString()).start();
        assertThat(made.waitFor()).isZero();
        assertThat(Confinement.check(target, real()).refusal()).contains(Refusal.NOT_A_FILE);
    }

    @Test
    void aConfinementResolvesTheRootOnce() {
        Confinement fence = new Confinement(root);
        Path first = fence.prepare();
        assertThat(fence.prepare()).isSameAs(first);
    }

    @Test
    void aConfinementSplitsAWalk() throws IOException {
        Path good = root.resolve("0.log");
        write(good, "x");
        Path outside = tmp.resolve("secrets");
        write(outside, "x");
        Path bad = root.resolve("1.log");
        link(outside, bad);
        Confinement.Split split = new Confinement(root).keep(List.of(good, bad));
        assertThat(split.kept()).containsExactly(good);
        assertThat(split.refused()).extracting(Verdict::refusal).containsExactly(Optional.of(Refusal.OUTSIDE));
    }

    @Test
    void aConfinementWithNothingToCheckIsEmpty() {
        assertThat(new Confinement(root).keep(List.of())).isEqualTo(new Confinement.Split(List.of(), List.of()));
    }

    @Test
    void anUnresolvableRootIsRefused() throws IOException {
        // A root whose links go round in a circle cannot be resolved at all.
        Path first = tmp.resolve("first");
        Path second = tmp.resolve("second");
        link(second, first);
        link(first, second);
        assertThatThrownBy(() -> Confinement.resolveRoot(first))
                .isInstanceOf(IngestError.class)
                .hasMessageContaining("cannot be resolved");
    }

    @Test
    void discoveryStillFindsOrdinaryFiles() {
        tree.writeCri("pump-bridge", List.of(LogFiles.criFull(TS, "up")));
        Discovery found = Discover.discover(tree.root());
        assertThat(found.follow()).extracting(LogPathInfo::unit).containsExactly("pump-bridge");
        assertThat(found.refused()).isEmpty();
    }

    @Test
    void discoveryRefusesALinkOutOfTheRoot() throws IOException {
        tree.writeCri("pump-bridge", List.of(LogFiles.criFull(TS, "up")));
        Path outside = tmp.resolve("elsewhere.log");
        write(outside, "not ours");
        Path link = tree.root().resolve("tank-gauge").resolve("0.log");
        Files.createDirectories(link.getParent());
        link(outside, link);
        Discovery found = Discover.discover(tree.root());
        assertThat(found.follow()).extracting(LogPathInfo::unit).containsExactly("pump-bridge");
        assertThat(found.reasons().get(SkipReason.OUTSIDE)).isEqualTo(1);
    }

    @Test
    void theRefusalIsReportedNotRaised() throws IOException {
        Path outside = tmp.resolve("elsewhere.log");
        write(outside, "not ours");
        Path link = tree.root().resolve("tank-gauge").resolve("0.log");
        Files.createDirectories(link.getParent());
        link(outside, link);
        Discovery found = Discover.discover(tree.root());
        assertThat(found.refused()).hasSize(1);
        assertThat(found.refused().get(0).refusal()).contains(Refusal.OUTSIDE);
    }
}
