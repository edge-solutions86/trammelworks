package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class EventLogTest {

    static EventLog logAt(Level level) {
        ManualClock clock = new ManualClock();
        clock.setWall("2025-03-04T11:30:00Z");
        return new EventLog(level, clock);
    }

    static EventLog logAt() {
        return logAt(Level.DEBUG);
    }

    @Test
    void levelParseIsCaseInsensitive() {
        assertThat(Level.parse("warn")).isSameAs(Level.WARN);
        assertThat(Level.parse(" ERROR ")).isSameAs(Level.ERROR);
    }

    @Test
    void levelParseRejectsJunk() {
        assertThatThrownBy(() -> Level.parse("chatty"))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("unknown level");
    }

    @Test
    void levelsAreOrdered() {
        assertThat(Level.DEBUG.severity()).isLessThan(Level.INFO.severity());
        assertThat(Level.INFO.severity()).isLessThan(Level.WARN.severity());
        assertThat(Level.WARN.severity()).isLessThan(Level.ERROR.severity());
    }

    @Test
    void anEventCarriesTheEnvelope() {
        EventLog log = logAt();
        log.info("spool_opened", "segments", 3);
        Map<String, Object> record = log.records().get(0);
        assertThat(record.get("at")).isEqualTo("2025-03-04T11:30:00.000000000Z");
        assertThat(record.get("level")).isEqualTo("INFO");
        assertThat(record.get("event")).isEqualTo("spool_opened");
        assertThat(record.get("segments")).isEqualTo(3L);
    }

    @Test
    void eventsBelowTheMinimumAreDropped() {
        EventLog log = logAt(Level.WARN);
        log.info("ignored");
        log.debug("also ignored");
        log.warn("kept");
        assertThat(log.records()).extracting(r -> r.get("event")).containsExactly("kept");
    }

    @Test
    void theMinimumIsInclusive() {
        EventLog log = logAt(Level.INFO);
        log.info("kept");
        assertThat(log.records()).hasSize(1);
    }

    @Test
    void aFieldMayNotShadowTheEnvelope() {
        EventLog log = logAt();
        assertThatThrownBy(() -> log.info("x", "level", "sneaky"))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("shadows the envelope");
    }

    @Test
    void theClashCheckNamesTheFirstOffender() {
        EventLog log = logAt();
        assertThatThrownBy(() -> log.info("x", "at", "now", "event", "other"))
                .isInstanceOf(StanchionError.class)
                .hasMessageContaining("shadows the envelope: at");
    }

    @Test
    void bindStampsContextOntoEveryEvent() {
        EventLog log = logAt().bind("site", "US-TX-0417", "unit", "pump-bridge");
        log.info("started");
        Map<String, Object> record = log.records().get(0);
        assertThat(record.get("site")).isEqualTo("US-TX-0417");
        assertThat(record.get("unit")).isEqualTo("pump-bridge");
    }

    @Test
    void bindLayers() {
        EventLog log = logAt().bind("site", "US-TX-0417").bind("unit", "tank-gauge");
        log.info("started");
        assertThat(log.records().get(0).get("unit")).isEqualTo("tank-gauge");
        assertThat(log.records().get(0).get("site")).isEqualTo("US-TX-0417");
    }

    @Test
    void bindDoesNotChangeTheParent() {
        EventLog parent = logAt();
        parent.bind("site", "US-TX-0417");
        parent.info("plain");
        assertThat(parent.records().get(0)).doesNotContainKey("site");
    }

    @Test
    void aBoundChildWritesIntoTheSameBuffer() {
        EventLog parent = logAt();
        EventLog child = parent.bind("site", "US-TX-0417");
        child.info("from the child");
        assertThat(parent.records()).hasSize(1);
    }

    @Test
    void explicitFieldsWinOverBoundContext() {
        EventLog log = logAt().bind("unit", "pump-bridge");
        log.info("started", "unit", "tank-gauge");
        assertThat(log.records().get(0).get("unit")).isEqualTo("tank-gauge");
    }

    @Test
    void linesAreJsonAndNewlineFree() {
        EventLog log = logAt();
        log.info("multi", "note", "first\nsecond");
        assertThat(log.lines()).hasSize(1);
        assertThat(log.lines().get(0)).doesNotContain("\n");
        assertThat(Json.loadsObject(log.lines().get(0)).get("note")).isEqualTo("first\nsecond");
    }

    @Test
    void linesIsACopy() {
        EventLog log = logAt();
        log.info("x");
        List<String> copy = new ArrayList<>(log.lines());
        copy.add("junk");
        assertThat(log.lines()).hasSize(1);
        assertThatThrownBy(() -> log.lines().add("junk")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aSinkReceivesOneLinePerEvent() {
        StringWriter buffer = new StringWriter();
        EventLog log = new EventLog(EventSink.of(buffer), Level.DEBUG, new ManualClock());
        log.info("one");
        log.info("two");
        assertThat(buffer.toString().chars().filter(c -> c == '\n').count()).isEqualTo(2);
    }

    @Test
    void countingSinkAdaptsACallable() {
        List<String> seen = new ArrayList<>();
        EventLog log = new EventLog(EventLog.countingSink(seen::add), Level.DEBUG, new ManualClock());
        log.warn("held");
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).endsWith("\n");
    }

    @Test
    void exceptionUsesTheStableCode() {
        EventLog log = logAt();
        log.exception("upload_failed", new ShipError("collector said no", "503"), "attempt", 2);
        Map<String, Object> record = log.records().get(0);
        assertThat(record.get("code")).isEqualTo("stanchion.ship");
        assertThat(record.get("message")).isEqualTo("collector said no");
        assertThat(record.get("attempt")).isEqualTo(2L);
        assertThat(record.get("level")).isEqualTo("ERROR");
    }

    @Test
    void exceptionLabelsAForeignError() {
        EventLog log = logAt();
        log.exception("upload_failed", new TimeoutException("no route"));
        assertThat(log.records().get(0).get("code")).isEqualTo("java.TimeoutException");
    }

    @Test
    void unserializableValuesFallBackToTheirText() {
        EventLog log = logAt();
        log.info("odd", "value", new Object());
        assertThat((String) log.records().get(0).get("value")).startsWith("java.lang.Object@");
    }

    @Test
    void nullLogKeepsNothingBelowError() {
        EventLog log = EventLog.nullLog();
        log.info("quiet");
        log.warn("still quiet");
        assertThat(log.records()).isEmpty();
    }
}
