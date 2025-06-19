from __future__ import annotations

import pytest

from stanchion.clockfix.session import (
    BootSession,
    SessionLedger,
    SyncEvent,
    parse_boot_id,
)
from stanchion.errors import ConfigError
from stanchion.timestamps import NS_PER_SECOND, NanoTime

BOOT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d"
OTHER = "aa" * 16


def sync(**over: object) -> SyncEvent:
    base: dict[str, object] = {
        "boot_id": BOOT,
        "monotonic_ns": 60 * NS_PER_SECOND,
        "before": NanoTime.parse("2025-03-04T11:29:53Z"),
        "after": NanoTime.parse("2025-03-04T11:30:00Z"),
    }
    base.update(over)
    return SyncEvent(**base)  # type: ignore[arg-type]


def test_parse_boot_id_accepts_plain_hex() -> None:
    assert parse_boot_id(BOOT) == BOOT


def test_parse_boot_id_strips_dashes_and_case() -> None:
    dashed = "4C1F2E3D-4C1F-2E3D-4C1F-2E3D4C1F2E3D"
    assert parse_boot_id(dashed) == BOOT


@pytest.mark.parametrize("raw", ["", "zz" * 16, "4c1f", "4c1f2e3d" * 5])
def test_parse_boot_id_rejects_anything_else(raw: str) -> None:
    with pytest.raises(ConfigError, match="malformed boot id"):
        parse_boot_id(raw)


def test_offset_is_what_the_clock_was_missing() -> None:
    assert sync().offset_ns == 7 * NS_PER_SECOND


def test_a_clock_that_ran_fast_gives_a_negative_offset() -> None:
    event = sync(
        before=NanoTime.parse("2025-03-04T11:30:05Z"),
        after=NanoTime.parse("2025-03-04T11:30:00Z"),
    )
    assert event.offset_ns == -5 * NS_PER_SECOND
    assert event.forwards is False


def test_a_clock_that_ran_slow_is_moved_forwards() -> None:
    assert sync().forwards is True


def test_a_small_correction_is_not_large() -> None:
    assert sync().large is False


def test_an_hour_of_error_counts_as_large() -> None:
    event = sync(after=NanoTime.parse("2025-03-04T12:30:00Z"))
    assert event.large is True


def test_a_large_correction_backwards_also_counts() -> None:
    event = sync(
        before=NanoTime.parse("2025-03-04T14:00:00Z"),
        after=NanoTime.parse("2025-03-04T11:30:00Z"),
    )
    assert event.large is True


def test_a_sync_normalizes_its_boot_id() -> None:
    assert sync(boot_id=BOOT.upper()).boot_id == BOOT


def test_a_sync_refuses_a_negative_monotonic() -> None:
    with pytest.raises(ConfigError, match="negative monotonic"):
        sync(monotonic_ns=-1)


def test_the_source_defaults_to_ntp() -> None:
    assert sync().source == "ntp"


def test_a_session_starts_unsynced() -> None:
    session = BootSession(boot_id=BOOT)
    assert session.synced is False
    assert session.syncs == []


def test_adding_a_sync_marks_the_session_synced() -> None:
    session = BootSession(boot_id=BOOT)
    session.add_sync(sync())
    assert session.synced is True


def test_a_session_refuses_a_sync_from_another_boot() -> None:
    session = BootSession(boot_id=BOOT)
    with pytest.raises(ConfigError, match="belongs to another boot"):
        session.add_sync(sync(boot_id=OTHER))


def test_syncs_are_kept_in_monotonic_order() -> None:
    session = BootSession(boot_id=BOOT)
    session.add_sync(sync(monotonic_ns=300 * NS_PER_SECOND))
    session.add_sync(sync(monotonic_ns=60 * NS_PER_SECOND))
    assert [event.monotonic_ns for event in session.syncs] == [
        60 * NS_PER_SECOND,
        300 * NS_PER_SECOND,
    ]


def test_a_session_built_with_syncs_sorts_them() -> None:
    session = BootSession(
        boot_id=BOOT,
        syncs=[sync(monotonic_ns=300), sync(monotonic_ns=60)],
    )
    assert [event.monotonic_ns for event in session.syncs] == [60, 300]


def test_a_session_built_with_a_foreign_sync_is_refused() -> None:
    with pytest.raises(ConfigError, match="belongs to another boot"):
        BootSession(boot_id=BOOT, syncs=[sync(boot_id=OTHER)])


def test_observe_tracks_the_span_of_the_session() -> None:
    session = BootSession(boot_id=BOOT)
    session.observe(100)
    session.observe(900)
    session.observe(400)
    assert session.first_monotonic_ns == 100
    assert session.last_monotonic_ns == 900
    assert session.duration_ns == 800


def test_observe_refuses_a_negative_reading() -> None:
    with pytest.raises(ConfigError, match="negative monotonic"):
        BootSession(boot_id=BOOT).observe(-1)


def test_adding_a_sync_also_observes_it() -> None:
    session = BootSession(boot_id=BOOT)
    session.add_sync(sync(monotonic_ns=500))
    assert session.last_monotonic_ns == 500


def test_bracket_of_an_unsynced_session_is_empty() -> None:
    assert BootSession(boot_id=BOOT).bracket(100) == (None, None)


def test_bracket_finds_the_sync_before() -> None:
    session = BootSession(boot_id=BOOT)
    first = sync(monotonic_ns=100)
    session.add_sync(first)
    before, after = session.bracket(500)
    assert before is first
    assert after is None


def test_bracket_finds_the_sync_after() -> None:
    session = BootSession(boot_id=BOOT)
    later = sync(monotonic_ns=900)
    session.add_sync(later)
    before, after = session.bracket(500)
    assert before is None
    assert after is later


def test_bracket_finds_both() -> None:
    session = BootSession(boot_id=BOOT)
    first, second = sync(monotonic_ns=100), sync(monotonic_ns=900)
    session.add_sync(first)
    session.add_sync(second)
    assert session.bracket(500) == (first, second)


def test_a_point_exactly_on_a_sync_counts_as_after_it() -> None:
    session = BootSession(boot_id=BOOT)
    event = sync(monotonic_ns=100)
    session.add_sync(event)
    before, after = session.bracket(100)
    assert before is event
    assert after is None


def test_the_ledger_creates_a_session_on_first_sight() -> None:
    ledger = SessionLedger()
    session = ledger.session(BOOT)
    assert session.boot_id == BOOT
    assert ledger.session(BOOT) is session


def test_the_ledger_normalizes_the_boot_id() -> None:
    ledger = SessionLedger()
    assert ledger.session(BOOT) is ledger.session(BOOT.upper())


def test_the_current_session_is_the_newest_boot() -> None:
    ledger = SessionLedger()
    ledger.session(BOOT)
    ledger.session(OTHER)
    current = ledger.current
    assert current is not None
    assert current.boot_id == OTHER


def test_an_empty_ledger_has_no_current_session() -> None:
    assert SessionLedger().current is None


def test_reboots_counts_the_gaps_not_the_sessions() -> None:
    ledger = SessionLedger()
    assert ledger.reboots == 0
    ledger.session(BOOT)
    assert ledger.reboots == 0
    ledger.session(OTHER)
    assert ledger.reboots == 1


def test_the_ledger_routes_a_sync_to_its_own_session() -> None:
    ledger = SessionLedger()
    ledger.add_sync(sync())
    assert ledger.session(BOOT).synced is True


def test_forget_drops_the_oldest_sessions() -> None:
    ledger = SessionLedger()
    ledger.session(BOOT)
    ledger.session(OTHER)
    ledger.session("bb" * 16)
    assert ledger.forget_all_but(1) == 2
    current = ledger.current
    assert current is not None
    assert current.boot_id == "bb" * 16


def test_forget_keeps_everything_when_there_is_room() -> None:
    ledger = SessionLedger()
    ledger.session(BOOT)
    assert ledger.forget_all_but(4) == 0


def test_forget_refuses_to_keep_nothing() -> None:
    with pytest.raises(ConfigError, match="at least one session"):
        SessionLedger().forget_all_but(0)
