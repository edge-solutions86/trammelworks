from __future__ import annotations

import json
from pathlib import Path

import pytest

from stanchion.clockfix.session import SessionLedger, SyncEvent
from stanchion.clockfix.store import (
    ClockStore,
    ledger_from_dict,
    ledger_to_dict,
    session_from_dict,
    sync_from_dict,
    sync_to_dict,
)
from stanchion.errors import ConfigError
from stanchion.timestamps import NS_PER_SECOND, NanoTime

BOOT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d"
OTHER = "aa" * 16


def sync(boot_id: str = BOOT, monotonic_ns: int = 600) -> SyncEvent:
    return SyncEvent(
        boot_id=boot_id,
        monotonic_ns=monotonic_ns,
        before=NanoTime.parse("2025-03-04T11:59:53Z"),
        after=NanoTime.parse("2025-03-04T12:00:00.000000042Z"),
    )


def filled() -> SessionLedger:
    ledger = SessionLedger()
    ledger.add_sync(sync())
    ledger.add_sync(sync(monotonic_ns=1200))
    ledger.session(OTHER).observe(50)
    return ledger


@pytest.fixture
def store(tmp_path: Path) -> ClockStore:
    return ClockStore(directory=tmp_path / "state")


def test_a_sync_round_trips_through_its_mapping() -> None:
    assert sync_from_dict(sync_to_dict(sync())) == sync()


def test_the_nanosecond_precision_survives() -> None:
    restored = sync_from_dict(sync_to_dict(sync()))
    assert restored.after.nanos_in_second == 42


def test_the_source_survives() -> None:
    event = SyncEvent(
        boot_id=BOOT,
        monotonic_ns=1,
        before=NanoTime(0),
        after=NanoTime(0),
        source="chrony",
    )
    assert sync_from_dict(sync_to_dict(event)).source == "chrony"


def test_a_missing_source_defaults_to_ntp() -> None:
    payload = sync_to_dict(sync())
    del payload["source"]
    assert sync_from_dict(payload).source == "ntp"


def test_a_sync_missing_a_field_is_refused() -> None:
    payload = sync_to_dict(sync())
    del payload["monotonic_ns"]
    with pytest.raises(ConfigError, match="missing a field"):
        sync_from_dict(payload)


def test_a_session_round_trips_with_its_syncs() -> None:
    ledger = filled()
    payload = ledger_to_dict(ledger)
    restored = ledger_from_dict(payload)
    assert len(restored.session(BOOT).syncs) == 2
    assert restored.session(BOOT).last_monotonic_ns == 1200


def test_a_session_missing_its_boot_id_is_refused() -> None:
    with pytest.raises(ConfigError, match="missing a field"):
        session_from_dict({"syncs": []})


def test_the_ledger_keeps_its_order() -> None:
    restored = ledger_from_dict(ledger_to_dict(filled()))
    assert restored.order == [BOOT, OTHER]
    current = restored.current
    assert current is not None
    assert current.boot_id == OTHER


def test_an_unknown_version_is_refused() -> None:
    payload = ledger_to_dict(filled())
    payload["version"] = 99
    with pytest.raises(ConfigError, match="unknown clock ledger version"):
        ledger_from_dict(payload)


def test_loading_from_an_empty_directory_gives_an_empty_ledger(store: ClockStore) -> None:
    assert store.load().order == []


def test_a_saved_ledger_comes_back(store: ClockStore) -> None:
    store.save(filled())
    restored = store.load()
    assert restored.order == [BOOT, OTHER]
    assert len(restored.session(BOOT).syncs) == 2


def test_save_creates_the_directory(tmp_path: Path) -> None:
    store = ClockStore(directory=tmp_path / "deep" / "state")
    store.save(SessionLedger())
    assert store.path.exists()


def test_save_leaves_no_temporary_file(store: ClockStore) -> None:
    store.save(filled())
    assert [p.name for p in store.directory.iterdir()] == ["clock.json"]


def test_saving_twice_replaces(store: ClockStore) -> None:
    store.save(filled())
    fresh = SessionLedger()
    fresh.session("bb" * 16)
    store.save(fresh)
    assert store.load().order == ["bb" * 16]


def test_save_prunes_to_the_keep_count(tmp_path: Path) -> None:
    store = ClockStore(directory=tmp_path / "state", keep_sessions=1)
    store.save(filled())
    assert store.load().order == [OTHER]


def test_pruning_also_shortens_the_ledger_in_memory(tmp_path: Path) -> None:
    store = ClockStore(directory=tmp_path / "state", keep_sessions=1)
    ledger = filled()
    store.save(ledger)
    assert ledger.order == [OTHER]


def test_a_keep_count_below_one_is_refused(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="at least 1"):
        ClockStore(directory=tmp_path, keep_sessions=0)


def test_a_corrupt_file_reads_as_empty(store: ClockStore) -> None:
    store.save(filled())
    store.path.write_text("{not json", encoding="utf-8")
    assert store.load().order == []
    assert store.corrupt == 1


def test_a_file_that_is_not_an_object_reads_as_empty(store: ClockStore) -> None:
    store.directory.mkdir(parents=True, exist_ok=True)
    store.path.write_text("[1,2]", encoding="utf-8")
    assert store.load().order == []
    assert store.corrupt == 1


def test_a_file_with_a_bad_session_reads_as_empty(store: ClockStore) -> None:
    store.directory.mkdir(parents=True, exist_ok=True)
    payload = ledger_to_dict(filled())
    payload["sessions"][0]["syncs"][0]["boot_id"] = "nonsense"
    store.path.write_text(json.dumps(payload), encoding="utf-8")
    assert store.load().order == []
    assert store.corrupt == 1


def test_the_file_is_indented_so_a_person_can_read_it(store: ClockStore) -> None:
    store.save(filled())
    assert store.path.read_text(encoding="utf-8").count("\n") > 5


def test_a_drift_model_still_works_after_a_round_trip(store: ClockStore) -> None:
    from stanchion.clockfix.drift import DriftModel

    ledger = SessionLedger()
    ledger.add_sync(
        SyncEvent(
            boot_id=BOOT,
            monotonic_ns=0,
            before=NanoTime.parse("2025-03-04T12:00:00Z"),
            after=NanoTime.parse("2025-03-04T12:00:00Z"),
        )
    )
    ledger.add_sync(
        SyncEvent(
            boot_id=BOOT,
            monotonic_ns=600 * NS_PER_SECOND,
            before=NanoTime.parse("2025-03-04T12:09:50Z"),
            after=NanoTime.parse("2025-03-04T12:10:00Z"),
        )
    )
    store.save(ledger)
    model = DriftModel(session=store.load().session(BOOT))
    assert model.correction_at(300 * NS_PER_SECOND).offset_ns == 5 * NS_PER_SECOND
