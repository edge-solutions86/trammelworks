package io.github.rkaushikdev.trammelworks.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.ShipError;
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

class ShipAckTest {

    static final String SEGMENT = "000000000041-payment.spool";
    static final String OTHER = "000000000042-chatter.spool";

    @TempDir
    Path tmp;

    AckStore store;

    @BeforeEach
    void setUp() {
        store = new AckStore(tmp.resolve("state"));
    }

    @Test
    void aFreshWatermarkIsAtZero() {
        Watermark mark = new Watermark(SEGMENT, 10);
        assertThat(mark.acked()).isZero();
        assertThat(mark.outstanding()).isEqualTo(10);
        assertThat(mark.done()).isFalse();
    }

    @Test
    void aWatermarkAtTheEndIsDone() {
        assertThat(new Watermark(SEGMENT, 10, 10, 0, 0).done()).isTrue();
    }

    @Test
    void anEmptySegmentIsDoneImmediately() {
        assertThat(new Watermark(SEGMENT, 0).done()).isTrue();
    }

    @Test
    void aWatermarkPastTheEndIsRefused() {
        assertThatThrownBy(() -> new Watermark(SEGMENT, 5, 6, 0, 0))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("outside the segment");
    }

    @Test
    void aNegativeTotalIsRefused() {
        assertThatThrownBy(() -> new Watermark(SEGMENT, -1))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("negative number of records");
    }

    @Test
    void advancingMovesTheWatermark() {
        Watermark mark = new Watermark(SEGMENT, 10);
        assertThat(mark.advance(4)).isEqualTo(4);
        assertThat(mark.advance(6)).isEqualTo(10);
        assertThat(mark.done()).isTrue();
    }

    @Test
    void advancingByZeroIsAllowed() {
        assertThat(new Watermark(SEGMENT, 10).advance(0)).isZero();
    }

    @Test
    void advancingByANegativeCountIsRefused() {
        assertThatThrownBy(() -> new Watermark(SEGMENT, 10).advance(-1))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("negative count");
    }

    @Test
    void advancingPastTheEndIsRefused() {
        Watermark mark = new Watermark(SEGMENT, 10);
        mark.advance(8);
        assertThatThrownBy(() -> mark.advance(5))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("runs past the end");
    }

    @Test
    void aWatermarkRoundTripsThroughItsMapping() {
        Watermark mark = new Watermark(SEGMENT, 10, 3, 2, 1);
        assertThat(Watermark.fromDict(mark.asDict())).isEqualTo(mark);
    }

    @Test
    void aWatermarkMissingAFieldIsRefused() {
        assertThatThrownBy(() -> Watermark.fromDict(Map.of("segment", SEGMENT)))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("missing a field");
    }

    @Test
    void theLedgerStartsAWatermarkOnFirstSight() {
        AckLedger ledger = new AckLedger();
        Watermark mark = ledger.mark(SEGMENT, 10);
        assertThat(mark.total()).isEqualTo(10);
        assertThat(ledger.mark(SEGMENT, 10)).isSameAs(mark);
    }

    @Test
    void aSegmentThatGrowsIsRefused() {
        AckLedger ledger = new AckLedger();
        ledger.mark(SEGMENT, 10);
        assertThatThrownBy(() -> ledger.mark(SEGMENT, 11))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("grew under the watermark");
    }

    @Test
    void aSegmentThatShrankIsTakenAsCompacted() {
        AckLedger ledger = new AckLedger();
        ledger.mark(SEGMENT, 10);
        ledger.acknowledge(SEGMENT, 6);
        Watermark mark = ledger.mark(SEGMENT, 4);
        assertThat(mark.total()).isEqualTo(4);
        assertThat(mark.acked()).isZero();
    }

    @Test
    void resizingStartsTheWatermarkAgain() {
        AckLedger ledger = new AckLedger();
        ledger.mark(SEGMENT, 10);
        ledger.acknowledge(SEGMENT, 4);
        assertThat(ledger.resize(SEGMENT, 6).acked()).isZero();
    }

    @Test
    void resizingASegmentNobodyShipsIsRefused() {
        assertThatThrownBy(() -> new AckLedger().resize(SEGMENT, 2))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("nobody is shipping");
    }

    @Test
    void aWatermarkCannotBeResizedUpwards() {
        assertThatThrownBy(() -> new Watermark(SEGMENT, 4).resize(9))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("cannot grow");
    }

    @Test
    void aWatermarkCannotBeResizedNegative() {
        assertThatThrownBy(() -> new Watermark(SEGMENT, 4).resize(-1))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("negative");
    }

    @Test
    void acknowledgingAdvancesTheRightSegment() {
        AckLedger ledger = new AckLedger();
        ledger.mark(SEGMENT, 10);
        ledger.mark(OTHER, 4);
        ledger.acknowledge(SEGMENT, 3);
        assertThat(ledger.marks().get(SEGMENT).acked()).isEqualTo(3);
        assertThat(ledger.marks().get(OTHER).acked()).isZero();
    }

    @Test
    void acknowledgingAnUnknownSegmentIsRefused() {
        assertThatThrownBy(() -> new AckLedger().acknowledge(SEGMENT, 1))
                .isInstanceOf(ShipError.class)
                .hasMessageContaining("nobody is shipping");
    }

    @Test
    void doneAndPendingSplitTheSegments() {
        AckLedger ledger = new AckLedger();
        ledger.mark(SEGMENT, 2);
        ledger.mark(OTHER, 2);
        ledger.acknowledge(SEGMENT, 2);
        assertThat(ledger.doneSegments()).containsExactly(SEGMENT);
        assertThat(ledger.pendingSegments()).containsExactly(OTHER);
    }

    @Test
    void theOutstandingTotalAddsUp() {
        AckLedger ledger = new AckLedger();
        ledger.mark(SEGMENT, 10);
        ledger.mark(OTHER, 4);
        ledger.acknowledge(SEGMENT, 6);
        assertThat(ledger.outstanding()).isEqualTo(8);
    }

    @Test
    void forgettingRemovesAWatermark() {
        AckLedger ledger = new AckLedger();
        ledger.mark(SEGMENT, 4);
        assertThat(ledger.forget(SEGMENT)).isTrue();
        assertThat(ledger.forget(SEGMENT)).isFalse();
    }

    @Test
    void sweepingDropsSegmentsThatAreGone() {
        AckLedger ledger = new AckLedger();
        ledger.mark(SEGMENT, 4);
        ledger.mark(OTHER, 4);
        assertThat(ledger.sweep(Set.of(SEGMENT))).isEqualTo(1);
        assertThat(ledger.pendingSegments()).containsExactly(SEGMENT);
    }

    @Test
    void sweepingWithEverythingLiveDropsNothing() {
        AckLedger ledger = new AckLedger();
        ledger.mark(SEGMENT, 4);
        assertThat(ledger.sweep(Set.of(SEGMENT))).isZero();
    }

    @Test
    void loadingFromNothingGivesAnEmptyLedger() {
        assertThat(store.load().marks()).isEmpty();
    }

    @Test
    void aSavedLedgerComesBack() {
        AckLedger ledger = new AckLedger();
        ledger.mark(SEGMENT, 10);
        ledger.acknowledge(SEGMENT, 4);
        store.save(ledger);
        AckLedger restored = store.load();
        assertThat(restored.marks().get(SEGMENT).acked()).isEqualTo(4);
        assertThat(restored.marks().get(SEGMENT).total()).isEqualTo(10);
    }

    @Test
    void saveCreatesTheDirectory() {
        AckStore deep = new AckStore(tmp.resolve("deep").resolve("state"));
        deep.save(new AckLedger());
        assertThat(deep.path()).exists();
    }

    @Test
    void saveLeavesNoTemporaryFile() throws IOException {
        store.save(new AckLedger());
        try (Stream<Path> found = Files.list(store.directory())) {
            assertThat(found.map(path -> path.getFileName().toString())).containsExactly("acks.json");
        }
    }

    @Test
    void aCorruptLedgerReadsAsEmpty() throws IOException {
        store.save(new AckLedger());
        Files.writeString(store.path(), "{broken");
        assertThat(store.load().marks()).isEmpty();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void aLedgerFromAnotherVersionReadsAsEmpty() throws IOException {
        Files.createDirectories(store.directory());
        Files.writeString(store.path(), Json.dumps(Maps.of("version", 99, "marks", List.of())));
        assertThat(store.load().marks()).isEmpty();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void aLedgerWithABrokenMarkReadsAsEmpty() throws IOException {
        Files.createDirectories(store.directory());
        Map<String, Object> payload =
                Maps.of("version", 1, "marks", List.of(Maps.of("segment", SEGMENT, "total", 4, "acked", 9)));
        Files.writeString(store.path(), Json.dumps(payload));
        assertThat(store.load().marks()).isEmpty();
        assertThat(store.corrupt()).isEqualTo(1);
    }

    @Test
    void theLedgerSurvivesARestartMidSegment() {
        AckLedger ledger = new AckLedger();
        ledger.mark(SEGMENT, 10);
        ledger.acknowledge(SEGMENT, 7);
        store.save(ledger);
        AckLedger resumed = store.load();
        assertThat(resumed.marks().get(SEGMENT).outstanding()).isEqualTo(3);
        assertThat(resumed.pendingSegments()).containsExactly(SEGMENT);
    }
}
