from __future__ import annotations

from typing import Any

import pytest

from stanchion.errors import ConfigError
from stanchion.redact.rules import (
    Action,
    Rule,
    apply_rule,
    rule_from_mapping,
    rules_from_mapping,
)


def rule(**over: object) -> Rule:
    base: dict[str, object] = {"name": "driver_id", "pattern": r"driver=(\d{4,10})"}
    base.update(over)
    return Rule(**base)  # type: ignore[arg-type]


def test_a_rule_needs_a_sane_name() -> None:
    with pytest.raises(ConfigError, match="malformed rule name"):
        rule(name="driver id")


def test_a_rule_needs_a_pattern_that_compiles() -> None:
    with pytest.raises(ConfigError, match="does not compile"):
        rule(pattern="(unclosed")


def test_several_groups_need_one_named_secret() -> None:
    with pytest.raises(ConfigError, match="must name one 'secret'"):
        rule(pattern=r"(a)(b)")


def test_a_named_secret_group_is_accepted_among_others() -> None:
    built = rule(pattern=r"(driver)=(?P<secret>\d+)")
    assert built.pattern.count("(") == 2


def test_a_drop_rule_may_not_keep_characters() -> None:
    with pytest.raises(ConfigError, match="cannot keep characters"):
        rule(action=Action.DROP, keep_first=2)


def test_a_negative_keep_is_refused() -> None:
    with pytest.raises(ConfigError, match="negative number of characters"):
        rule(action=Action.MASK, keep_last=-1)


def test_a_drop_rule_replaces_the_group_only() -> None:
    line, hits = apply_rule(rule(), "auth driver=88213 pump=3")
    assert line == "auth driver=[redacted] pump=3"
    assert [hit.rule for hit in hits] == ["driver_id"]


def test_the_hit_records_what_was_removed() -> None:
    _, hits = apply_rule(rule(), "auth driver=88213 pump=3")
    assert hits[0].text == "88213"


def test_the_hit_span_points_at_the_original_text() -> None:
    line = "auth driver=88213 pump=3"
    _, hits = apply_rule(rule(), line)
    assert line[hits[0].start : hits[0].end] == "88213"


def test_a_whole_match_is_used_when_there_is_no_group() -> None:
    built = rule(name="reg", pattern=r"[A-Z]{2}\d{2} [A-Z]{3}")
    line, _ = apply_rule(built, "vehicle AB12 CDE arrived")
    assert line == "vehicle [redacted] arrived"


def test_the_named_group_wins_over_the_first_one() -> None:
    built = rule(name="loyalty", pattern=r"(card)=(?P<secret>\d+)")
    line, _ = apply_rule(built, "loyalty card=998877")
    assert line == "loyalty card=[redacted]"


def test_a_mask_rule_keeps_the_ends() -> None:
    built = rule(action=Action.MASK, keep_first=2, keep_last=2)
    line, _ = apply_rule(built, "auth driver=88213")
    assert line == "auth driver=88*13"


def test_a_mask_that_would_keep_everything_masks_it_all() -> None:
    built = rule(action=Action.MASK, keep_first=4, keep_last=4)
    line, _ = apply_rule(built, "auth driver=8821")
    assert line == "auth driver=****"


def test_a_mask_with_no_keeps_hides_everything() -> None:
    built = rule(action=Action.MASK)
    line, _ = apply_rule(built, "auth driver=88213")
    assert line == "auth driver=*****"


def test_a_custom_marker_is_used() -> None:
    built = rule(marker="<gone>")
    line, _ = apply_rule(built, "auth driver=88213")
    assert line == "auth driver=<gone>"


def test_every_occurrence_is_replaced() -> None:
    line, hits = apply_rule(rule(), "driver=1111 then driver=2222")
    assert line == "driver=[redacted] then driver=[redacted]"
    assert len(hits) == 2


def test_a_line_with_no_match_is_untouched() -> None:
    line, hits = apply_rule(rule(), "nozzle 1 lifted")
    assert line == "nozzle 1 lifted"
    assert hits == []


def test_applying_a_rule_twice_is_stable() -> None:
    once, _ = apply_rule(rule(), "driver=88213")
    twice, hits = apply_rule(rule(), once)
    assert twice == once
    assert hits == []


def test_rule_from_mapping_builds_a_drop_rule() -> None:
    built = rule_from_mapping("driver_id", {"pattern": r"driver=(\d+)"})
    assert built.action is Action.DROP


def test_rule_from_mapping_builds_a_mask_rule() -> None:
    raw: dict[str, Any] = {"pattern": r"(\d{11})", "action": "mask", "keep_last": 4}
    built = rule_from_mapping("loyalty", raw)
    assert built.action is Action.MASK
    assert built.keep_last == 4


def test_rule_from_mapping_needs_a_pattern() -> None:
    with pytest.raises(ConfigError, match="no pattern"):
        rule_from_mapping("driver_id", {"action": "drop"})


def test_rule_from_mapping_rejects_an_unknown_key() -> None:
    with pytest.raises(ConfigError, match="unknown rule key"):
        rule_from_mapping("driver_id", {"pattern": "x", "keep": 2})


def test_rule_from_mapping_rejects_an_unknown_action() -> None:
    with pytest.raises(ConfigError, match="unknown rule action"):
        rule_from_mapping("driver_id", {"pattern": "x", "action": "shred"})


def test_rules_from_mapping_keeps_the_written_order() -> None:
    built = rules_from_mapping(
        {
            "loyalty": {"pattern": r"(\d{11})"},
            "driver_id": {"pattern": r"driver=(\d+)"},
        }
    )
    assert [item.name for item in built] == ["loyalty", "driver_id"]


def test_rules_from_mapping_rejects_a_non_mapping() -> None:
    with pytest.raises(ConfigError, match="must be a mapping"):
        rules_from_mapping({"loyalty": r"\d+"})


def test_an_empty_rule_table_is_fine() -> None:
    assert rules_from_mapping({}) == []
