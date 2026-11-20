package io.github.rkaushikdev.trammelworks.spool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.SpoolError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpoolEvictTest {

    static final PriorityTable TABLE = new PriorityTable();

    @TempDir
    Path tmp;

    static SegmentInfo segment(long sequence, String klass, long size) {
        return segment(sequence, klass, size, Path.of("/spool"));
    }

    static SegmentInfo segment(long sequence, String klass, long size, Path root) {
        return new SegmentInfo(root.resolve(String.format("%012d-%s.spool", sequence, klass)), sequence, klass, size);
    }

    static SegmentInfo onDisk(Path root, long sequence, String klass, int size) throws IOException {
        Files.createDirectories(root);
        SegmentInfo info = segment(sequence, klass, size, root);
        Files.write(info.path(), "x".repeat(size).getBytes());
        return info;
    }

    static List<SegmentInfo> chatter(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> segment(index, "chatter", 100))
                .toList();
    }

    @Test
    void needingNothingPlansNothing() {
        EvictionPlan plan = Eviction.planEviction(List.of(segment(0, "chatter", 100)), TABLE, 0);
        assertThat(plan.empty()).isTrue();
        assertThat(plan.sufficient()).isTrue();
    }

    @Test
    void aNegativeNeedIsRefused() {
        assertThatThrownBy(() -> Eviction.planEviction(List.of(), TABLE, -1))
                .isInstanceOf(SpoolError.class)
                .hasMessageContaining("negative number of bytes");
    }

    @Test
    void theLowestClassGoesFirst() {
        List<SegmentInfo> segments = List.of(segment(0, "standard", 100), segment(1, "chatter", 100));
        assertThat(Eviction.planEviction(segments, TABLE, 50).victims())
                .extracting(SegmentInfo::klass)
                .containsExactly("chatter");
    }

    @Test
    void theOldestGoesFirstInsideAClass() {
        List<SegmentInfo> segments = List.of(segment(5, "chatter", 100), segment(2, "chatter", 100));
        assertThat(Eviction.planEviction(segments, TABLE, 50).victims())
                .extracting(SegmentInfo::sequence)
                .containsExactly(2L);
    }

    @Test
    void aProtectedClassIsNeverChosen() {
        EvictionPlan plan = Eviction.planEviction(List.of(segment(0, "payment", 1000)), TABLE, 500);
        assertThat(plan.empty()).isTrue();
        assertThat(plan.sufficient()).isFalse();
        assertThat(plan.protectedBytes()).isEqualTo(1000);
    }

    @Test
    void aPinnedSegmentIsNeverChosen() {
        SegmentInfo victim = segment(0, "chatter", 1000);
        EvictionPlan plan = Eviction.planEviction(List.of(victim), TABLE, 500, Set.of(victim.path()));
        assertThat(plan.empty()).isTrue();
        assertThat(plan.pinnedBytes()).isEqualTo(1000);
    }

    @Test
    void enoughSegmentsAreTakenToCoverTheNeed() {
        EvictionPlan plan = Eviction.planEviction(chatter(5), TABLE, 250);
        assertThat(plan.victims()).hasSize(3);
        assertThat(plan.freed()).isEqualTo(300);
        assertThat(plan.sufficient()).isTrue();
    }

    @Test
    void noMoreAreTakenThanNeeded() {
        assertThat(Eviction.planEviction(chatter(5), TABLE, 100).victims()).hasSize(1);
    }

    @Test
    void aClassIsEmptiedBeforeTheNextOneIsTouched() {
        List<SegmentInfo> segments = List.of(segment(0, "chatter", 100), segment(1, "standard", 100));
        assertThat(Eviction.planEviction(segments, TABLE, 150).victims())
                .extracting(SegmentInfo::klass)
                .containsExactly("chatter", "standard");
    }

    @Test
    void theShortfallIsReportedWhenThereIsNotEnough() {
        EvictionPlan plan = Eviction.planEviction(List.of(segment(0, "chatter", 100)), TABLE, 400);
        assertThat(plan.sufficient()).isFalse();
        assertThat(plan.shortfall()).isEqualTo(300);
    }

    @Test
    void aSpoolFullOfProtectedRecordsCannotBeFreed() {
        List<SegmentInfo> segments = IntStream.range(0, 10)
                .mapToObj(index -> segment(index, "payment", 100))
                .toList();
        EvictionPlan plan = Eviction.planEviction(segments, TABLE, 500);
        assertThat(plan.sufficient()).isFalse();
        assertThat(plan.freed()).isZero();
    }

    @Test
    void protectedAndPinnedBytesAreReportedEvenAtZeroNeed() {
        List<SegmentInfo> segments = List.of(segment(0, "payment", 100), segment(1, "chatter", 40));
        EvictionPlan plan =
                Eviction.planEviction(segments, TABLE, 0, Set.of(segments.get(1).path()));
        assertThat(plan.protectedBytes()).isEqualTo(100);
        assertThat(plan.pinnedBytes()).isEqualTo(40);
    }

    @Test
    void evictableBytesExcludesProtectedAndPinned() {
        SegmentInfo pinned = segment(2, "chatter", 40);
        List<SegmentInfo> segments = List.of(segment(0, "payment", 100), segment(1, "chatter", 30), pinned);
        assertThat(Eviction.evictableBytes(segments, TABLE, Set.of(pinned.path())))
                .isEqualTo(30);
    }

    @Test
    void evictableBytesOfAnEmptySpoolIsZero() {
        assertThat(Eviction.evictableBytes(List.of(), TABLE)).isZero();
    }

    @Test
    void applyingAPlanDeletesTheFiles() throws IOException {
        SegmentInfo victim = onDisk(tmp, 0, "chatter", 100);
        EvictionResult result = Eviction.applyEviction(Eviction.planEviction(List.of(victim), TABLE, 50));
        assertThat(result.count()).isEqualTo(1);
        assertThat(result.freed()).isEqualTo(100);
        assertThat(victim.path()).doesNotExist();
    }

    @Test
    void applyingAnEmptyPlanDoesNothing() throws IOException {
        SegmentInfo keeper = onDisk(tmp, 0, "chatter", 100);
        EvictionResult result = Eviction.applyEviction(Eviction.planEviction(List.of(keeper), TABLE, 0));
        assertThat(result.count()).isZero();
        assertThat(keeper.path()).exists();
    }

    @Test
    void aSegmentAlreadyGoneStillCountsAsFreed() throws IOException {
        SegmentInfo victim = onDisk(tmp, 0, "chatter", 100);
        EvictionPlan plan = Eviction.planEviction(List.of(victim), TABLE, 50);
        Files.delete(victim.path());
        EvictionResult result = Eviction.applyEviction(plan);
        assertThat(result.freed()).isEqualTo(100);
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void severalSegmentsAreDeletedInOrder() throws IOException {
        List<SegmentInfo> segments = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            segments.add(onDisk(tmp, index, "chatter", 100));
        }
        EvictionResult result = Eviction.applyEviction(Eviction.planEviction(segments, TABLE, 250));
        assertThat(result.deleted()).extracting(SegmentInfo::sequence).containsExactly(0L, 1L, 2L);
        assertThat(segments).noneMatch(item -> Files.exists(item.path()));
    }

    @Test
    void aDirectoryInPlaceOfASegmentIsReportedNotRaised() throws IOException {
        Path root = tmp.resolve("spool");
        Files.createDirectories(root);
        SegmentInfo info = segment(0, "chatter", 100, root);
        Files.createDirectories(info.path());
        EvictionResult result = Eviction.applyEviction(Eviction.planEviction(List.of(info), TABLE, 50));
        assertThat(result.count()).isZero();
        assertThat(result.failed()).hasSize(1);
    }
}
