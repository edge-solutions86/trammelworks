from __future__ import annotations

from datetime import timedelta

import pytest

from stanchion.archive.retention import (
    CLASS_FLOORS,
    Hold,
    RetentionPolicy,
    decide,
    floor_for,
    format_retention,
    policy_from_mapping,
)
from stanchion.errors import ConfigError, RetentionError
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-09-01T12:00:00Z")


def at(raw: str) -> NanoTime:
    return NanoTime.parse(raw)


def test_payment_has_the_scheme_floor() -> None:
    assert floor_for("payment") == timedelta(days=180)


def test_an_unknown_class_has_no_floor() -> None:
    assert floor_for("brand-new") == timedelta(0)


def test_the_defaults_satisfy_every_floor() -> None:
    policy = RetentionPolicy()
    for klass in CLASS_FLOORS:
        assert policy.window_for(klass) >= floor_for(klass)


def test_a_window_below_the_floor_is_refused() -> None:
    with pytest.raises(ConfigError, match="below the scheme floor"):
        RetentionPolicy(windows={"payment": timedelta(days=30)})


def test_the_refusal_names_both_durations() -> None:
    with pytest.raises(ConfigError) as caught:
        RetentionPolicy(windows={"payment": timedelta(days=30)})
    assert caught.value.detail is not None
    assert "30d" in caught.value.detail
    assert "180d" in caught.value.detail


def test_a_negative_window_is_refused() -> None:
    with pytest.raises(ConfigError, match="cannot be negative"):
        RetentionPolicy(windows={"chatter": timedelta(days=-1)})


def test_a_longer_window_than_the_floor_is_allowed() -> None:
    policy = RetentionPolicy(windows={"payment": timedelta(days=365)})
    assert policy.window_for("payment") == timedelta(days=365)


def test_a_class_with_no_window_still_gets_its_floor() -> None:
    policy = RetentionPolicy(windows={"chatter": timedelta(days=1)})
    assert policy.window_for("payment") == timedelta(days=180)


def test_the_cutoff_is_the_window_back_from_now() -> None:
    policy = RetentionPolicy(windows={"chatter": timedelta(days=7)})
    assert policy.cutoff_for("chatter", NOW) == at("2025-08-25T12:00:00Z")


def test_describe_reads_in_human_units() -> None:
    line = RetentionPolicy().describe()
    assert "payment 180d" in line
    assert "chatter 7d" in line


def test_a_hold_needs_a_name() -> None:
    with pytest.raises(RetentionError, match="needs a name"):
        Hold(name="  ", reason="dispute", placed_at=NOW)


def test_a_hold_cannot_end_before_it_starts() -> None:
    with pytest.raises(RetentionError, match="cannot end before it starts"):
        Hold(
            name="case-41",
            reason="dispute",
            placed_at=NOW,
            covers_from=at("2025-09-01T00:00:00Z"),
            covers_until=at("2025-08-01T00:00:00Z"),
        )


def test_an_open_ended_hold_covers_everything() -> None:
    hold = Hold(name="case-41", reason="dispute", placed_at=NOW)
    assert hold.open_ended is True
    assert hold.covers(at("2020-01-01T00:00:00Z")) is True
    assert hold.covers(at("2030-01-01T00:00:00Z")) is True


def test_a_windowed_hold_covers_only_its_window() -> None:
    hold = Hold(
        name="case-41",
        reason="dispute",
        placed_at=NOW,
        covers_from=at("2025-03-01T00:00:00Z"),
        covers_until=at("2025-03-31T23:59:59Z"),
    )
    assert hold.covers(at("2025-03-15T00:00:00Z")) is True
    assert hold.covers(at("2025-04-15T00:00:00Z")) is False
    assert hold.covers(at("2025-02-15T00:00:00Z")) is False


def test_a_hold_with_only_a_start_runs_forwards() -> None:
    hold = Hold(
        name="case-41", reason="dispute", placed_at=NOW, covers_from=at("2025-03-01T00:00:00Z")
    )
    assert hold.covers(at("2030-01-01T00:00:00Z")) is True
    assert hold.covers(at("2020-01-01T00:00:00Z")) is False


def test_a_recent_record_is_kept() -> None:
    policy = RetentionPolicy(windows={"chatter": timedelta(days=7)})
    found = decide(policy, [], "chatter", at("2025-08-30T12:00:00Z"), NOW)
    assert found.deletable is False
    assert found.reason == "inside the window"


def test_an_old_record_may_go() -> None:
    policy = RetentionPolicy(windows={"chatter": timedelta(days=7)})
    found = decide(policy, [], "chatter", at("2025-08-01T12:00:00Z"), NOW)
    assert found.deletable is True


def test_a_record_exactly_on_the_cutoff_is_kept() -> None:
    policy = RetentionPolicy(windows={"chatter": timedelta(days=7)})
    found = decide(policy, [], "chatter", at("2025-08-25T12:00:00Z"), NOW)
    assert found.deletable is False


def test_a_hold_beats_an_expired_window() -> None:
    policy = RetentionPolicy(windows={"chatter": timedelta(days=7)})
    hold = Hold(name="case-41", reason="dispute", placed_at=NOW)
    found = decide(policy, [hold], "chatter", at("2020-01-01T00:00:00Z"), NOW)
    assert found.deletable is False
    assert found.reason == "held"
    assert found.hold == "case-41"


def test_a_hold_that_does_not_cover_the_record_does_not_save_it() -> None:
    policy = RetentionPolicy(windows={"chatter": timedelta(days=7)})
    hold = Hold(
        name="case-41",
        reason="dispute",
        placed_at=NOW,
        covers_from=at("2025-01-01T00:00:00Z"),
        covers_until=at("2025-01-31T00:00:00Z"),
    )
    found = decide(policy, [hold], "chatter", at("2025-08-01T12:00:00Z"), NOW)
    assert found.deletable is True


def test_the_first_covering_hold_is_named() -> None:
    policy = RetentionPolicy()
    holds = [
        Hold(name="first", reason="a", placed_at=NOW),
        Hold(name="second", reason="b", placed_at=NOW),
    ]
    assert decide(policy, holds, "chatter", at("2020-01-01T00:00:00Z"), NOW).hold == "first"


def test_the_payment_floor_keeps_a_record_a_site_wanted_gone() -> None:
    policy = RetentionPolicy(windows={"chatter": timedelta(days=1)})
    found = decide(policy, [], "payment", at("2025-06-01T12:00:00Z"), NOW)
    assert found.deletable is False


def test_the_mapping_builder_reads_written_durations() -> None:
    policy = policy_from_mapping({"chatter": "3d", "payment": "200d"})
    assert policy.window_for("chatter") == timedelta(days=3)
    assert policy.window_for("payment") == timedelta(days=200)


def test_the_mapping_builder_keeps_the_defaults_for_absent_classes() -> None:
    policy = policy_from_mapping({"chatter": "3d"})
    assert policy.window_for("payment") == timedelta(days=180)


def test_an_empty_section_gives_the_defaults() -> None:
    assert policy_from_mapping({}) == RetentionPolicy()


def test_the_mapping_builder_still_enforces_the_floor() -> None:
    with pytest.raises(ConfigError, match="below the scheme floor"):
        policy_from_mapping({"payment": "30d"})


def test_a_whole_number_of_days_is_written_in_days() -> None:
    assert format_retention(timedelta(days=180)) == "180d"
    assert format_retention(timedelta(days=1)) == "1d"


def test_a_part_day_falls_back_to_the_general_form() -> None:
    assert format_retention(timedelta(hours=36)) == "1d12h"


def test_zero_is_written_as_zero_seconds() -> None:
    assert format_retention(timedelta(0)) == "0s"
