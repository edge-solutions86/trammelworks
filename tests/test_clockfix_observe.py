from __future__ import annotations

from stanchion.clockfix.observe import (
    SyncObserver,
    observe_chrony,
    observe_timesyncd,
    truth_at,
)
from stanchion.clockfix.session import SessionLedger
from stanchion.records import LogRecord, Stream
from stanchion.timestamps import NS_PER_SECOND, NanoTime

BOOT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d"
AT = "2025-03-04T12:00:00Z"


def record(message: str, unit: str = "chronyd", monotonic: int | None = 600) -> LogRecord:
    labels: dict[str, str] = {}
    if monotonic is not None:
        labels["monotonic_ns"] = str(monotonic)
    return LogRecord(
        site="US-TX-0417",
        unit=unit,
        stream=Stream.STDOUT,
        observed_at=NanoTime.parse(AT),
        message=message,
        labels=labels,
    )


def test_a_clock_that_was_behind_is_moved_forwards() -> None:
    line = record("System clock wrong by -7.000000 seconds, adjustment started")
    found = observe_chrony(line, BOOT)
    assert found is not None
    assert found.event.offset_ns == 7 * NS_PER_SECOND
    assert found.event.forwards is True


def test_a_clock_that_was_ahead_is_moved_back() -> None:
    line = record("System clock wrong by 3.500000 seconds, adjustment started")
    found = observe_chrony(line, BOOT)
    assert found is not None
    assert found.event.offset_ns == -3_500_000_000
    assert found.event.forwards is False


def test_the_before_reading_is_what_the_clock_said() -> None:
    found = observe_chrony(record("System clock wrong by -7.0 seconds"), BOOT)
    assert found is not None
    assert found.event.before == NanoTime.parse(AT)


def test_the_after_reading_is_the_truth() -> None:
    found = observe_chrony(record("System clock wrong by -7.0 seconds"), BOOT)
    assert found is not None
    assert found.event.after == NanoTime.parse("2025-03-04T12:00:07Z")


def test_a_whole_number_of_seconds_is_read() -> None:
    found = observe_chrony(record("System clock wrong by -2 seconds"), BOOT)
    assert found is not None
    assert found.event.offset_ns == 2 * NS_PER_SECOND


def test_a_plus_sign_is_accepted() -> None:
    found = observe_chrony(record("System clock wrong by +1.0 seconds"), BOOT)
    assert found is not None
    assert found.event.offset_ns == -NS_PER_SECOND


def test_the_monotonic_label_becomes_the_position() -> None:
    found = observe_chrony(record("System clock wrong by -1.0 seconds", monotonic=4096), BOOT)
    assert found is not None
    assert found.event.monotonic_ns == 4096


def test_a_chrony_line_without_a_position_is_not_usable() -> None:
    line = record("System clock wrong by -7.0 seconds", monotonic=None)
    assert observe_chrony(line, BOOT) is None


def test_an_unrelated_chrony_line_is_ignored() -> None:
    assert observe_chrony(record("Selected source 10.0.0.1"), BOOT) is None


def test_a_chrony_event_is_marked_exact() -> None:
    found = observe_chrony(record("System clock wrong by -1.0 seconds"), BOOT)
    assert found is not None
    assert found.exact is True


def test_timesyncd_initial_sync_is_recognised() -> None:
    line = record(
        "Initial synchronization to time server 10.0.0.1:123 (ntp.internal).",
        unit="systemd-timesyncd",
    )
    found = observe_timesyncd(line, BOOT)
    assert found is not None
    assert found.event.source == "timesyncd"


def test_a_later_timesyncd_sync_is_recognised() -> None:
    line = record("Synchronization to time server 10.0.0.1:123.", unit="systemd-timesyncd")
    assert observe_timesyncd(line, BOOT) is not None


def test_a_timesyncd_event_measures_nothing() -> None:
    line = record("Initial synchronization to time server 10.0.0.1:123.", unit="systemd-timesyncd")
    found = observe_timesyncd(line, BOOT)
    assert found is not None
    assert found.event.offset_ns == 0
    assert found.exact is False


def test_an_unrelated_timesyncd_line_is_ignored() -> None:
    line = record("Timed out waiting for reply", unit="systemd-timesyncd")
    assert observe_timesyncd(line, BOOT) is None


def test_truth_at_subtracts_the_error() -> None:
    assert truth_at(record("x"), -7.0) == NanoTime.parse("2025-03-04T12:00:07Z")
    assert truth_at(record("x"), 7.0) == NanoTime.parse("2025-03-04T11:59:53Z")


def observer() -> SyncObserver:
    return SyncObserver(ledger=SessionLedger(), default_boot_id=BOOT)


def test_the_observer_files_a_chrony_event() -> None:
    watcher = observer()
    watcher.look(record("System clock wrong by -7.0 seconds"))
    assert watcher.filed == 1
    assert watcher.ledger.session(BOOT).synced is True


def test_the_observer_ignores_other_units() -> None:
    watcher = observer()
    assert watcher.look(record("System clock wrong by -7.0 seconds", unit="pump-bridge")) is None
    assert watcher.seen == 0


def test_the_observer_counts_what_it_looked_at() -> None:
    watcher = observer()
    watcher.look(record("Selected source 10.0.0.1"))
    assert watcher.seen == 1
    assert watcher.filed == 0


def test_the_observer_notes_a_sync_it_could_not_place() -> None:
    watcher = observer()
    watcher.look(record("System clock wrong by -7.0 seconds", monotonic=None))
    assert watcher.ignored_no_position == 1
    assert watcher.filed == 0


def test_an_unplaceable_non_sync_line_is_not_counted_as_lost() -> None:
    watcher = observer()
    watcher.look(record("Selected source 10.0.0.1", monotonic=None))
    assert watcher.ignored_no_position == 0


def test_the_boot_label_wins_over_the_default() -> None:
    watcher = observer()
    line = record("System clock wrong by -7.0 seconds")
    tagged = line.with_labels(boot_id="aa" * 16)
    found = watcher.look(tagged)
    assert found is not None
    assert found.boot_id == "aa" * 16


def test_a_malformed_boot_label_is_refused_quietly() -> None:
    watcher = observer()
    line = record("System clock wrong by -7.0 seconds").with_labels(boot_id="nonsense")
    assert watcher.look(line) is None
    assert watcher.filed == 0


def test_look_all_returns_only_the_hits() -> None:
    watcher = observer()
    found = watcher.look_all(
        [
            record("System clock wrong by -1.0 seconds"),
            record("Selected source 10.0.0.1"),
            record("System clock wrong by -2.0 seconds"),
        ]
    )
    assert len(found) == 2
    assert watcher.filed == 2


def test_two_events_land_in_the_same_session_in_order() -> None:
    watcher = observer()
    watcher.look(record("System clock wrong by -1.0 seconds", monotonic=900))
    watcher.look(record("System clock wrong by -2.0 seconds", monotonic=100))
    positions = [event.monotonic_ns for event in watcher.ledger.session(BOOT).syncs]
    assert positions == [100, 900]


def test_a_timesyncd_line_is_filed_too() -> None:
    watcher = observer()
    watcher.look(
        record("Initial synchronization to time server 10.0.0.1:123.", unit="systemd-timesyncd")
    )
    assert watcher.filed == 1
