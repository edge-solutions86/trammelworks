from __future__ import annotations

from pathlib import Path

import pytest

from stanchion.errors import SpoolError
from stanchion.records import LogRecord, Stream
from stanchion.spool.budget import Budget
from stanchion.spool.priority import PriorityTable
from stanchion.spool.reader import read_segment
from stanchion.spool.store import Spool
from stanchion.telemetry import Registry
from stanchion.timestamps import NanoTime

TABLE = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})


def record(unit: str = "pump-bridge", message: str = "nozzle 1 lifted") -> LogRecord:
    return LogRecord(
        site="US-TX-0417",
        unit=unit,
        stream=Stream.STDOUT,
        observed_at=NanoTime.parse("2025-03-04T12:00:00Z"),
        message=message,
    )


def spool(tmp_path: Path, **over: object) -> Spool:
    settings: dict[str, object] = {
        "directory": tmp_path / "spool",
        "budget": Budget(limit_bytes=1_000_000, reserve_ratio=0.1),
        "table": TABLE,
        "segment_bytes": 4096,
    }
    settings.update(over)
    return Spool(**settings)  # type: ignore[arg-type]


def test_a_record_is_written_and_receipted(tmp_path: Path) -> None:
    store = spool(tmp_path)
    receipt = store.append(record())
    assert receipt.klass == "standard"
    assert receipt.offset == 0
    assert receipt.size > 0


def test_a_payment_record_goes_to_the_payment_class(tmp_path: Path) -> None:
    store = spool(tmp_path)
    assert store.append(record(unit="payment-kernel")).klass == "payment"


def test_two_classes_get_separate_segments(tmp_path: Path) -> None:
    store = spool(tmp_path)
    store.append(record(unit="payment-kernel"))
    store.append(record(unit="price-sign"))
    sealed = store.seal_all()
    assert sorted(item.klass for item in sealed) == ["chatter", "payment"]


def test_the_records_read_back(tmp_path: Path) -> None:
    store = spool(tmp_path)
    store.append(record(message="one"))
    store.append(record(message="two"))
    sealed = store.seal_all()
    payloads = read_segment(sealed[0].path).payloads
    assert [item["message"] for item in payloads] == ["one", "two"]


def test_the_record_survives_the_round_trip_whole(tmp_path: Path) -> None:
    store = spool(tmp_path)
    original = record(message="auth approved")
    store.append(original)
    payload = read_segment(store.seal_all()[0].path).payloads[0]
    assert payload["site"] == original.site
    assert payload["unit"] == original.unit
    assert payload["observed_at"] == original.observed_at.isoformat()


def test_the_offset_advances_within_a_segment(tmp_path: Path) -> None:
    store = spool(tmp_path)
    first = store.append(record())
    second = store.append(record())
    assert second.offset == first.size


def test_a_segment_rolls_when_it_is_full(tmp_path: Path) -> None:
    store = spool(tmp_path, segment_bytes=200)
    sequences = {store.append(record(message="x" * 40)).sequence for _ in range(6)}
    assert len(sequences) > 1


def test_a_rolled_segment_is_sealed_and_listed(tmp_path: Path) -> None:
    store = spool(tmp_path, segment_bytes=200)
    for _ in range(6):
        store.append(record(message="y" * 40))
    assert len(store.segments()) >= 1


def test_usage_counts_the_open_segment(tmp_path: Path) -> None:
    store = spool(tmp_path)
    receipt = store.append(record())
    assert store.usage().used == receipt.size
    assert store.usage().open_bytes == receipt.size


def test_usage_counts_sealed_segments(tmp_path: Path) -> None:
    store = spool(tmp_path)
    receipt = store.append(record())
    store.seal_all()
    assert store.usage().sealed_bytes == receipt.size
    assert store.usage().open_bytes == 0


def test_a_segment_larger_than_the_budget_is_refused(tmp_path: Path) -> None:
    with pytest.raises(SpoolError, match="larger than the usable budget"):
        spool(tmp_path, budget=Budget(limit_bytes=1000), segment_bytes=4096)


def test_a_full_spool_evicts_the_lowest_class(tmp_path: Path) -> None:
    store = spool(tmp_path, budget=Budget(limit_bytes=4000, reserve_ratio=0.1), segment_bytes=300)
    for _ in range(20):
        store.append(record(unit="price-sign", message="z" * 60))
    for _ in range(20):
        store.append(record(unit="payment-kernel", message="z" * 60))
    remaining = {item.klass for item in store.segments()}
    assert "payment" in remaining


def test_eviction_is_counted(tmp_path: Path) -> None:
    metrics = Registry()
    store = spool(
        tmp_path,
        budget=Budget(limit_bytes=3000, reserve_ratio=0.1),
        segment_bytes=300,
        metrics=metrics,
    )
    for _ in range(30):
        store.append(record(unit="price-sign", message="z" * 60))
    assert metrics.snapshot()["spool_evicted_segments"] > 0


def test_a_spool_full_of_protected_records_refuses_the_write(tmp_path: Path) -> None:
    store = spool(tmp_path, budget=Budget(limit_bytes=2000, reserve_ratio=0.1), segment_bytes=300)
    with pytest.raises(SpoolError, match="nothing more can be evicted"):
        for _ in range(200):
            store.append(record(unit="payment-kernel", message="q" * 60))


def test_a_pinned_segment_survives_eviction(tmp_path: Path) -> None:
    store = spool(tmp_path, budget=Budget(limit_bytes=4000, reserve_ratio=0.1), segment_bytes=300)
    for _ in range(6):
        store.append(record(unit="price-sign", message="z" * 60))
    store.seal_all()
    keeper = store.segments()[0]
    store.pin(keeper.path)
    for _ in range(20):
        store.append(record(unit="price-sign", message="z" * 60))
    assert keeper.path.exists()


def test_unpinning_lets_it_go_again(tmp_path: Path) -> None:
    store = spool(tmp_path, budget=Budget(limit_bytes=4000, reserve_ratio=0.1), segment_bytes=300)
    for _ in range(6):
        store.append(record(unit="price-sign", message="z" * 60))
    store.seal_all()
    keeper = store.segments()[0]
    store.pin(keeper.path)
    store.unpin(keeper.path)
    for _ in range(30):
        store.append(record(unit="price-sign", message="z" * 60))
    assert not keeper.path.exists()


def test_opening_recovers_an_interrupted_segment(tmp_path: Path) -> None:
    first = spool(tmp_path)
    first.append(record(message="before the power cut"))
    first.detach()
    second = spool(tmp_path)
    found = second.open()
    assert len(found) == 1
    assert found[0].frames == 1


def test_recovery_is_counted(tmp_path: Path) -> None:
    first = spool(tmp_path)
    first.append(record())
    first.detach()
    metrics = Registry()
    spool(tmp_path, metrics=metrics).open()
    assert metrics.snapshot()["spool_recovered_segments"] == 1


def test_opening_a_clean_spool_recovers_nothing(tmp_path: Path) -> None:
    assert spool(tmp_path).open() == []


def test_sequence_numbers_are_not_reused_across_a_restart(tmp_path: Path) -> None:
    first = spool(tmp_path)
    first.append(record())
    first.seal_all()
    second = spool(tmp_path)
    receipt = second.append(record())
    assert receipt.sequence == 1


def test_append_all_returns_a_receipt_each(tmp_path: Path) -> None:
    store = spool(tmp_path)
    receipts = store.append_all([record(), record()])
    assert len(receipts) == 2
    assert receipts[0].offset == 0


def test_the_metrics_count_records_and_bytes(tmp_path: Path) -> None:
    metrics = Registry()
    store = spool(tmp_path, metrics=metrics)
    receipt = store.append(record())
    snapshot = metrics.snapshot()
    assert snapshot["spool_records"] == 1
    assert snapshot["spool_bytes"] == receipt.size


def test_sealing_an_empty_spool_gives_nothing(tmp_path: Path) -> None:
    assert spool(tmp_path).seal_all() == []


def test_detach_leaves_a_part_file_for_recovery(tmp_path: Path) -> None:
    store = spool(tmp_path)
    store.append(record())
    store.detach()
    assert store.segments() == []
    assert list((tmp_path / "spool").glob("*.part"))


def test_detach_on_an_untouched_spool_is_a_no_op(tmp_path: Path) -> None:
    store = spool(tmp_path)
    store.detach()
    assert store.writers == {}


def test_a_detached_spool_writes_into_a_fresh_segment(tmp_path: Path) -> None:
    # The detached part file keeps its sequence number, so the next write does
    # not reopen it. Reopening would be worse: recovery expects to find that
    # file exactly as the crash left it.
    store = spool(tmp_path)
    first = store.append(record())
    store.detach()
    second = store.append(record())
    assert second.sequence == first.sequence + 1
    assert second.offset == 0
