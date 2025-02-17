from __future__ import annotations

import io
import json

import pytest

from stanchion.clocks import ManualClock
from stanchion.errors import ShipError, StanchionError
from stanchion.eventlog import EventLog, Level, counting_sink, null_log


def log_at(level: Level = Level.DEBUG) -> EventLog:
    clock = ManualClock()
    clock.set_wall("2025-03-04T11:30:00Z")
    return EventLog(minimum=level, clock=clock)


def test_level_parse_is_case_insensitive() -> None:
    assert Level.parse("warn") is Level.WARN
    assert Level.parse(" ERROR ") is Level.ERROR


def test_level_parse_rejects_junk() -> None:
    with pytest.raises(StanchionError, match="unknown level"):
        Level.parse("chatty")


def test_levels_are_ordered() -> None:
    assert Level.DEBUG < Level.INFO < Level.WARN < Level.ERROR


def test_an_event_carries_the_envelope() -> None:
    log = log_at()
    log.info("spool_opened", segments=3)
    record = log.records()[0]
    assert record["at"] == "2025-03-04T11:30:00.000000000Z"
    assert record["level"] == "INFO"
    assert record["event"] == "spool_opened"
    assert record["segments"] == 3


def test_events_below_the_minimum_are_dropped() -> None:
    log = log_at(Level.WARN)
    log.info("ignored")
    log.debug("also ignored")
    log.warn("kept")
    assert [r["event"] for r in log.records()] == ["kept"]


def test_the_minimum_is_inclusive() -> None:
    log = log_at(Level.INFO)
    log.info("kept")
    assert len(log.records()) == 1


def test_a_field_may_not_shadow_the_envelope() -> None:
    log = log_at()
    with pytest.raises(StanchionError, match="shadows the envelope"):
        log.info("x", level="sneaky")


def test_the_clash_check_names_the_first_offender() -> None:
    log = log_at()
    with pytest.raises(StanchionError, match="shadows the envelope: at"):
        log.info("x", at="now", event="other")


def test_bind_stamps_context_onto_every_event() -> None:
    log = log_at().bind(site="US-TX-0417", unit="pump-bridge")
    log.info("started")
    record = log.records()[0]
    assert record["site"] == "US-TX-0417"
    assert record["unit"] == "pump-bridge"


def test_bind_layers() -> None:
    log = log_at().bind(site="US-TX-0417").bind(unit="tank-gauge")
    log.info("started")
    assert log.records()[0]["unit"] == "tank-gauge"
    assert log.records()[0]["site"] == "US-TX-0417"


def test_bind_does_not_change_the_parent() -> None:
    parent = log_at()
    parent.bind(site="US-TX-0417")
    parent.info("plain")
    assert "site" not in parent.records()[0]


def test_a_bound_child_writes_into_the_same_buffer() -> None:
    parent = log_at()
    child = parent.bind(site="US-TX-0417")
    child.info("from the child")
    assert len(parent.records()) == 1


def test_explicit_fields_win_over_bound_context() -> None:
    log = log_at().bind(unit="pump-bridge")
    log.info("started", unit="tank-gauge")
    assert log.records()[0]["unit"] == "tank-gauge"


def test_lines_are_json_and_newline_free() -> None:
    log = log_at()
    log.info("multi", note="first\nsecond")
    assert len(log.lines) == 1
    assert "\n" not in log.lines[0]
    assert json.loads(log.lines[0])["note"] == "first\nsecond"


def test_lines_is_a_copy() -> None:
    log = log_at()
    log.info("x")
    log.lines.append("junk")
    assert len(log.lines) == 1


def test_a_sink_receives_one_line_per_event() -> None:
    buffer = io.StringIO()
    clock = ManualClock()
    log = EventLog(sink=buffer, minimum=Level.DEBUG, clock=clock)
    log.info("one")
    log.info("two")
    assert buffer.getvalue().count("\n") == 2


def test_counting_sink_adapts_a_callable() -> None:
    seen: list[str] = []
    log = EventLog(sink=counting_sink(seen.append), minimum=Level.DEBUG, clock=ManualClock())
    log.warn("held")
    assert len(seen) == 1
    assert seen[0].endswith("\n")


def test_exception_uses_the_stable_code() -> None:
    log = log_at()
    log.exception("upload_failed", ShipError("collector said no", detail="503"), attempt=2)
    record = log.records()[0]
    assert record["code"] == "stanchion.ship"
    assert record["message"] == "collector said no"
    assert record["attempt"] == 2
    assert record["level"] == "ERROR"


def test_exception_labels_a_foreign_error() -> None:
    log = log_at()
    log.exception("upload_failed", TimeoutError("no route"))
    assert log.records()[0]["code"] == "python.TimeoutError"


def test_unserializable_values_fall_back_to_str() -> None:
    log = log_at()
    log.info("odd", value=object())
    assert log.records()[0]["value"].startswith("<object object")


def test_null_log_keeps_nothing_below_error() -> None:
    log = null_log()
    log.info("quiet")
    log.warn("still quiet")
    assert log.records() == []
