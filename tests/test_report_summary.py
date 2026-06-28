from __future__ import annotations

import json
from dataclasses import fields

from stanchion.report.summary import (
    FIELD_NAMES,
    ClockSummary,
    IngestSummary,
    ShipSummary,
    SpoolSummary,
    Summary,
    from_metrics,
    section_dict,
)
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-03-04T23:59:00Z")
SITE = "US-TX-0417"


def healthy() -> Summary:
    built = Summary(site=SITE, at=NOW)
    built.ingest = IngestSummary(files_followed=4, records=1200)
    built.ship = ShipSummary(
        batches=6, records=1200, bytes_sent=40_000, meter_used=100, meter_allowance=1000
    )
    built.spool = SpoolSummary(used=100, limit=1000, segments=2)
    built.clock = ClockSummary(boots=1, syncs=2, drift_ms=40)
    built.archived = 1200
    built.check()
    return built


def test_a_healthy_site_reports_ok() -> None:
    built = healthy()
    assert built.healthy is True
    assert "ok" in built.render_text().splitlines()[0]


def test_a_healthy_site_has_no_problems() -> None:
    assert healthy().check() == []


def test_a_site_following_nothing_is_flagged() -> None:
    built = healthy()
    built.ingest.files_followed = 0
    assert "no container log files" in " ".join(built.check())
    assert built.healthy is False


def test_a_refused_batch_is_flagged() -> None:
    built = healthy()
    built.ship.terminal = 3
    assert "3 batches were refused" in " ".join(built.check())


def test_a_nearly_spent_allowance_is_flagged() -> None:
    built = healthy()
    built.ship.meter_used = 950
    assert "allowance is 95% spent" in " ".join(built.check())


def test_an_allowance_under_the_threshold_is_not_flagged() -> None:
    built = healthy()
    built.ship.meter_used = 800
    assert not any("allowance" in item for item in built.check())


def test_an_eviction_is_flagged() -> None:
    built = healthy()
    built.spool.evicted = 2
    assert "2 spool segments were evicted" in " ".join(built.check())


def test_a_nearly_full_spool_is_flagged() -> None:
    built = healthy()
    built.spool.used = 850
    assert "spool is 85% full" in " ".join(built.check())


def test_a_clock_that_never_synced_is_flagged() -> None:
    built = healthy()
    built.clock.syncs = 0
    assert "clock has not been corrected" in " ".join(built.check())


def test_a_large_drift_makes_the_clock_unhealthy() -> None:
    built = healthy()
    built.clock.drift_ms = 120_000
    assert built.clock.healthy is False


def test_bytes_lost_to_a_rotation_are_flagged() -> None:
    built = healthy()
    built.ingest.lost_bytes = 4096
    assert "4096 bytes were lost" in " ".join(built.check())


def test_several_problems_are_all_reported() -> None:
    built = healthy()
    built.ship.terminal = 1
    built.spool.evicted = 1
    assert len(built.check()) == 2


def test_the_text_form_names_the_site_and_time() -> None:
    line = healthy().render_text().splitlines()[0]
    assert line.startswith(SITE)
    assert "2025-03-04T23:59:00" in line


def test_the_text_form_has_a_line_per_subsystem() -> None:
    lines = healthy().render_text().splitlines()
    assert any(item.strip().startswith("ingest") for item in lines)
    assert any(item.strip().startswith("ship") for item in lines)
    assert any(item.strip().startswith("spool") for item in lines)
    assert any(item.strip().startswith("clock") for item in lines)


def test_the_text_form_shows_sizes_in_human_units() -> None:
    built = healthy()
    built.spool.used = 1024
    built.spool.limit = 1048576
    assert "1KiB of 1MiB" in built.render_text()


def test_the_text_form_lists_the_problems() -> None:
    built = healthy()
    built.spool.evicted = 1
    assert "! 1 spool segments were evicted unsent" in built.render_text()


def test_an_unhealthy_summary_says_attention() -> None:
    built = healthy()
    built.spool.evicted = 1
    assert "ATTENTION" in built.render_text()


def test_the_redaction_tally_appears_when_there_is_one() -> None:
    built = healthy()
    built.redacted = {"card_pan": 4, "driver_id": 2}
    assert "6 removed across 2 rules" in built.render_text()


def test_the_redaction_line_is_absent_when_nothing_was_removed() -> None:
    assert "redact" not in healthy().render_text()


def test_the_json_form_round_trips() -> None:
    payload = json.loads(healthy().render_json())
    assert payload["site"] == SITE
    assert payload["ingest"]["records"] == 1200
    assert payload["healthy"] is True


def test_the_json_form_lists_the_problems() -> None:
    built = healthy()
    built.spool.evicted = 1
    built.check()
    assert json.loads(built.render_json())["problems"]


def test_the_json_form_is_sorted_so_a_diff_is_readable() -> None:
    rendered = healthy().render_json()
    assert rendered == healthy().render_json()
    assert rendered.index('"archived"') < rendered.index('"site"')


def test_building_from_metrics_reads_what_is_there() -> None:
    built = from_metrics(
        SITE,
        NOW,
        {
            "ingest_files_followed": 3,
            "ingest_records": 90,
            "ship_batches": 2,
            "ship_records": 90,
            "ship_bytes": 4096,
            "spool_bytes": 512,
            "spool_limit": 4096,
        },
    )
    assert built.ingest.records == 90
    assert built.ship.batches == 2
    assert built.spool.ratio == 0.125


def test_building_from_an_empty_snapshot_does_not_fail() -> None:
    built = from_metrics(SITE, NOW, {})
    assert built.ingest.records == 0
    assert built.healthy is False


def test_an_empty_snapshot_flags_that_nothing_is_followed() -> None:
    assert "no container log files" in " ".join(from_metrics(SITE, NOW, {}).problems)


def test_a_missing_spool_limit_does_not_divide_by_zero() -> None:
    built = from_metrics(SITE, NOW, {"spool_bytes": 4})
    assert built.spool.ratio > 0


AT = NOW

SECTION_KEYS = {
    "ingest": IngestSummary,
    "ship": ShipSummary,
    "spool": SpoolSummary,
    "clock": ClockSummary,
}


def test_every_section_field_reaches_the_json() -> None:
    """The check the hand written version did not have.

    ``ShipSummary.blocked`` was counted, carried and then left out of the
    JSON, so the number that says a site is hitting its allowance never left
    the box. Walking the dataclasses is what stops that happening again.
    """
    payload = Summary(site=SITE, at=AT).as_dict()
    for name, kind in SECTION_KEYS.items():
        expected = {FIELD_NAMES.get(item.name, item.name) for item in fields(kind)}
        assert set(payload[name]) == expected, name


def test_the_blocked_count_is_in_the_summary() -> None:
    summary = Summary(site=SITE, at=AT)
    summary.ship.blocked = 4
    assert summary.as_dict()["ship"]["blocked"] == 4


def test_a_section_dict_renames_only_what_it_has_to() -> None:
    found = section_dict(IngestSummary(files_followed=3, records=9))
    assert found["files"] == 3
    assert found["records"] == 9
    assert "files_followed" not in found


def test_the_renamed_keys_are_the_ones_the_estate_parses() -> None:
    payload = Summary(site=SITE, at=AT).as_dict()
    assert "files" in payload["ingest"]
    assert "bytes" in payload["ship"]
    assert "unknown" in payload["clock"]


def test_a_blocked_site_still_renders_as_text() -> None:
    summary = Summary(site=SITE, at=AT)
    summary.ship.blocked = 2
    assert SITE in summary.render_text()
