from __future__ import annotations

import pytest

from stanchion.errors import ConfigError
from stanchion.ingest.multiline import MultilineRule, Stitcher, rules_from_mapping
from stanchion.records import LogRecord, Stream
from stanchion.timestamps import NanoTime

UNIT = "payment-kernel"


def rec(message: str, *, stream: Stream = Stream.STDOUT, at: str = "11:30:00") -> LogRecord:
    return LogRecord(
        site="US-TX-0417",
        unit=UNIT,
        stream=stream,
        observed_at=NanoTime.parse(f"2025-03-04T{at}Z"),
        message=message,
    )


def indent_rule(**over: object) -> MultilineRule:
    base: dict[str, object] = {"unit": UNIT, "continuation": r"^\s+"}
    base.update(over)
    return MultilineRule(**base)  # type: ignore[arg-type]


def seq_rule(**over: object) -> MultilineRule:
    base: dict[str, object] = {"unit": UNIT, "start": r"^\d+ "}
    base.update(over)
    return MultilineRule(**base)  # type: ignore[arg-type]


def test_a_rule_needs_one_framing() -> None:
    with pytest.raises(ConfigError, match="exactly one of start or continuation"):
        MultilineRule(unit=UNIT)


def test_a_rule_refuses_both_framings() -> None:
    with pytest.raises(ConfigError, match="exactly one of start or continuation"):
        MultilineRule(unit=UNIT, start="^a", continuation=r"^\s")


def test_a_rule_refuses_a_max_below_two() -> None:
    with pytest.raises(ConfigError, match="at least 2"):
        indent_rule(max_lines=1)


def test_a_rule_refuses_a_pattern_that_does_not_compile() -> None:
    with pytest.raises(ConfigError, match="does not compile"):
        MultilineRule(unit=UNIT, continuation="(unclosed")


def test_continuation_matches_an_indented_frame() -> None:
    rule = indent_rule()
    assert rule.continues("    at Pump.reset(Pump.java:41)") is True
    assert rule.continues("something happened") is False


def test_start_framing_is_the_inverse() -> None:
    rule = seq_rule()
    assert rule.continues("0001 auth requested") is False
    assert rule.continues("  stack frame") is True


def test_negate_flips_a_continuation_rule() -> None:
    rule = indent_rule(negate=True)
    assert rule.continues("    indented") is False
    assert rule.continues("plain") is True


def test_a_single_line_passes_through_unchanged() -> None:
    stitcher = Stitcher(rule=indent_rule())
    assert stitcher.feed(rec("first")) == []
    out = stitcher.feed(rec("second"))
    assert len(out) == 1
    assert out[0].message == "first"
    assert out[0].labels == {}


def test_indented_lines_are_folded_into_the_line_above() -> None:
    stitcher = Stitcher(rule=indent_rule())
    stitcher.feed(rec("java.lang.IllegalStateException: pump busy"))
    stitcher.feed(rec("    at Pump.reset(Pump.java:41)"))
    stitcher.feed(rec("    at Main.run(Main.java:9)"))
    out = stitcher.feed(rec("next event"))
    assert len(out) == 1
    assert out[0].message.splitlines() == [
        "java.lang.IllegalStateException: pump busy",
        "    at Pump.reset(Pump.java:41)",
        "    at Main.run(Main.java:9)",
    ]


def test_the_stitched_record_says_how_many_lines_it_holds() -> None:
    stitcher = Stitcher(rule=indent_rule())
    stitcher.feed(rec("head"))
    stitcher.feed(rec("  one"))
    out = stitcher.feed(rec("next"))
    assert out[0].labels["stitched"] == "2"


def test_the_first_line_owns_the_timestamp() -> None:
    stitcher = Stitcher(rule=indent_rule())
    stitcher.feed(rec("head", at="11:30:00"))
    stitcher.feed(rec("  one", at="11:30:09"))
    out = stitcher.feed(rec("next", at="11:31:00"))
    assert out[0].observed_at == NanoTime.parse("2025-03-04T11:30:00Z")


def test_the_streams_are_held_separately() -> None:
    stitcher = Stitcher(rule=indent_rule())
    stitcher.feed(rec("out head", stream=Stream.STDOUT))
    stitcher.feed(rec("err head", stream=Stream.STDERR))
    stitcher.feed(rec("  err frame", stream=Stream.STDERR))
    out = stitcher.feed(rec("out next", stream=Stream.STDOUT))
    assert len(out) == 1
    assert out[0].message == "out head"
    assert stitcher.held_streams() == (Stream.STDOUT, Stream.STDERR)


def test_a_continuation_on_the_other_stream_does_not_join() -> None:
    stitcher = Stitcher(rule=indent_rule())
    stitcher.feed(rec("head", stream=Stream.STDOUT))
    stitcher.feed(rec("  frame", stream=Stream.STDERR))
    remaining = stitcher.flush()
    messages = sorted(r.message for r in remaining)
    assert messages == ["  frame", "head"]


def test_flush_emits_what_is_held() -> None:
    stitcher = Stitcher(rule=indent_rule())
    stitcher.feed(rec("head"))
    stitcher.feed(rec("  frame"))
    out = stitcher.flush()
    assert len(out) == 1
    assert out[0].labels["stitched"] == "2"
    assert stitcher.held_streams() == ()


def test_flush_of_an_empty_stitcher_gives_nothing() -> None:
    assert Stitcher(rule=indent_rule()).flush() == []


def test_a_run_longer_than_the_cap_stops_growing() -> None:
    stitcher = Stitcher(rule=indent_rule(max_lines=3))
    stitcher.feed(rec("head"))
    for index in range(5):
        stitcher.feed(rec(f"  frame {index}"))
    out = stitcher.flush()
    assert out[0].message.count("\n") == 2
    assert out[0].labels["truncated"] == "true"


def test_the_first_line_after_a_capped_run_starts_a_new_event() -> None:
    stitcher = Stitcher(rule=indent_rule(max_lines=2))
    stitcher.feed(rec("head"))
    stitcher.feed(rec("  one"))
    stitcher.feed(rec("  two"))
    out = stitcher.feed(rec("plain"))
    assert len(out) == 1
    assert out[0].message == "head\n  one"


def test_sources_from_every_line_are_kept() -> None:
    from stanchion.records import SourceRef

    stitcher = Stitcher(rule=indent_rule())
    head = rec("head")
    head = LogRecord(
        site=head.site,
        unit=head.unit,
        stream=head.stream,
        observed_at=head.observed_at,
        message=head.message,
        sources=(SourceRef(path="a", offset=0, length=10),),
    )
    frame = LogRecord(
        site=head.site,
        unit=head.unit,
        stream=head.stream,
        observed_at=head.observed_at,
        message="  frame",
        sources=(SourceRef(path="a", offset=10, length=10),),
    )
    stitcher.feed(head)
    stitcher.feed(frame)
    out = stitcher.flush()
    assert [ref.offset for ref in out[0].sources] == [0, 10]


def test_rules_from_mapping_builds_the_table() -> None:
    table = rules_from_mapping({UNIT: {"continuation": r"^\s+", "max_lines": 40}})
    assert table[UNIT].max_lines == 40
    assert table[UNIT].continuation == r"^\s+"


def test_rules_from_mapping_rejects_a_non_mapping() -> None:
    with pytest.raises(ConfigError, match="must be a mapping"):
        rules_from_mapping({UNIT: "indent"})


def test_rules_from_mapping_rejects_an_unknown_key() -> None:
    with pytest.raises(ConfigError, match="unknown multiline key"):
        rules_from_mapping({UNIT: {"continuation": "^x", "maxlines": 4}})


def test_rules_from_mapping_still_refuses_both_framings() -> None:
    with pytest.raises(ConfigError, match="exactly one of"):
        rules_from_mapping({UNIT: {"continuation": "^x", "start": "^y"}})
