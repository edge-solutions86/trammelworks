from __future__ import annotations

import random
from pathlib import Path

import pytest

from stanchion.clocks import ManualClock
from stanchion.records import LogRecord, Stream
from stanchion.ship.backoff import Backoff, BackoffPolicy, Outcome
from stanchion.ship.batch import BatchLimits
from stanchion.ship.meter import Meter
from stanchion.ship.session import Shipper
from stanchion.ship.transport import MemoryTransport, Response
from stanchion.spool.budget import Budget
from stanchion.spool.priority import PriorityTable
from stanchion.spool.store import Spool
from stanchion.telemetry import Registry
from stanchion.timestamps import NanoTime

SITE = "US-TX-0417"
TABLE = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})


def record(unit: str = "pump-bridge", message: str = "nozzle 1 lifted") -> LogRecord:
    return LogRecord(
        site=SITE,
        unit=unit,
        stream=Stream.STDOUT,
        observed_at=NanoTime.parse("2025-03-04T12:00:00Z"),
        message=message,
    )


def fill(directory: Path, unit: str, count: int, segment_bytes: int = 4096) -> Spool:
    store = Spool(
        directory=directory,
        budget=Budget(limit_bytes=1_000_000, reserve_ratio=0.1),
        table=TABLE,
        segment_bytes=segment_bytes,
    )
    for index in range(count):
        store.append(record(unit, message=f"line {index}"))
    store.seal_all()
    return store


def metered(allowance: int, used: int) -> Meter:
    """A meter that has already been through a rollover and spent some.

    Building one and charging it is not enough: the shipper rolls the period on
    every pass, and a meter that has never rolled rolls on the first one and
    starts the period clean.
    """
    meter = Meter(allowance_bytes=allowance, anchor_day=1)
    meter.roll(NanoTime.parse("2025-03-04T12:00:00Z"))
    meter.charge(used)
    return meter


def shipper(directory: Path, transport: MemoryTransport, **over: object) -> Shipper:
    settings: dict[str, object] = {
        "site": SITE,
        "directory": directory,
        "transport": transport,
        "table": TABLE,
        "meter": Meter(allowance_bytes=10_000_000, anchor_day=1),
        "clock": ManualClock(),
        "backoff": Backoff(rng=random.Random(3)),
    }
    settings.update(over)
    return Shipper(**settings)  # type: ignore[arg-type]


def test_an_empty_spool_ships_nothing(tmp_path: Path) -> None:
    report = shipper(tmp_path / "spool", MemoryTransport()).run_once()
    assert report.batches_sent == 0
    assert report.idle is True


def test_a_segment_is_shipped(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 3)
    transport = MemoryTransport()
    report = shipper(spool, transport).run_once()
    assert report.records_sent == 3
    assert len(transport.received) == 1


def test_an_acknowledged_segment_is_deleted(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 3)
    shipper(spool, MemoryTransport()).run_once()
    assert list(spool.glob("*.spool")) == []


def test_a_second_pass_has_nothing_to_do(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 3)
    ship = shipper(spool, MemoryTransport())
    ship.run_once()
    assert ship.run_once().idle is True


def test_payment_ships_before_chatter(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "price-sign", 2)
    fill(spool, "payment-kernel", 2)
    transport = MemoryTransport()
    shipper(spool, transport).run_once()
    sources = [batch.source for batch in transport.received]
    assert "payment" in sources[0]


def test_the_oldest_segment_of_a_class_goes_first(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 2, segment_bytes=256)
    transport = MemoryTransport()
    shipper(spool, transport).run_once()
    sources = [batch.source for batch in transport.received]
    assert sources == sorted(sources)


def test_a_batch_carries_the_segment_as_its_source(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 2)
    transport = MemoryTransport()
    shipper(spool, transport).run_once()
    assert transport.received[0].source.endswith(".spool")


def test_the_records_go_out_in_order(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 4)
    transport = MemoryTransport()
    shipper(spool, transport).run_once()
    messages = [item["message"] for batch in transport.received for item in batch.records]
    assert messages == ["line 0", "line 1", "line 2", "line 3"]


def test_a_big_segment_is_cut_into_several_batches(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 6)
    transport = MemoryTransport()
    report = shipper(spool, transport, limits=BatchLimits(max_records=2)).run_once()
    assert report.batches_sent == 3
    assert report.records_sent == 6


def test_a_retryable_failure_stops_the_pass(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 6)
    transport = MemoryTransport(script=[Response(Outcome.RETRYABLE, 503, message="gateway")])
    report = shipper(spool, transport, limits=BatchLimits(max_records=2)).run_once()
    assert report.batches_sent == 0
    assert report.stopped == "gateway"
    assert report.wait_seconds > 0


def test_a_failed_segment_is_not_deleted(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 3)
    transport = MemoryTransport(script=[Response(Outcome.RETRYABLE, 503)])
    shipper(spool, transport).run_once()
    assert list(spool.glob("*.spool"))


def test_a_partly_acknowledged_segment_resumes_where_it_stopped(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 6)
    transport = MemoryTransport(
        script=[Response(Outcome.OK, 200), Response(Outcome.RETRYABLE, 503)]
    )
    ship = shipper(spool, transport, limits=BatchLimits(max_records=2))
    ship.run_once()
    transport.script = []
    ship.run_once()
    messages = [item["message"] for batch in transport.received for item in batch.records]
    assert messages.count("line 0") == 1
    assert "line 5" in messages


def test_a_throttle_hint_is_used_as_the_wait(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 2)
    transport = MemoryTransport(script=[Response(Outcome.THROTTLED, 429, retry_after=42.0)])
    report = shipper(spool, transport).run_once()
    assert report.wait_seconds == 42.0


def test_a_terminal_refusal_stops_that_segment_and_is_counted(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 3)
    transport = MemoryTransport(script=[Response(Outcome.TERMINAL, 400, message="bad body")])
    report = shipper(spool, transport).run_once()
    assert report.terminal == 1
    assert report.batches_sent == 0


def test_a_terminal_refusal_does_not_push_the_link_into_backoff(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 3)
    transport = MemoryTransport(script=[Response(Outcome.TERMINAL, 400)])
    ship = shipper(spool, transport)
    ship.run_once()
    assert ship.backoff.failures == 0


def test_the_meter_is_charged_for_what_went(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 3)
    ship = shipper(spool, MemoryTransport())
    report = ship.run_once()
    assert ship.meter.used_bytes == report.bytes_sent
    assert report.bytes_sent > 0


def test_the_meter_is_charged_even_for_a_failed_send(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 3)
    transport = MemoryTransport(script=[Response(Outcome.RETRYABLE, 503)])
    ship = shipper(spool, transport)
    ship.run_once()
    assert ship.meter.used_bytes > 0


def test_a_class_at_its_floor_is_blocked_and_named(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "price-sign", 3)
    ship = shipper(spool, MemoryTransport(), meter=metered(1000, 900))
    report = ship.run_once()
    assert report.batches_sent == 0
    assert report.blocked_by_meter


def test_payment_still_ships_when_chatter_is_blocked(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "price-sign", 2)
    fill(spool, "payment-kernel", 2)
    transport = MemoryTransport()
    report = shipper(spool, transport, meter=metered(100_000, 75_000)).run_once()
    assert report.batches_sent >= 1
    assert all("payment" in batch.source for batch in transport.received)


def test_the_segment_being_shipped_is_pinned(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 2)
    seen: list[int] = []

    class Watcher(MemoryTransport):
        def send(self, batch):  # type: ignore[no-untyped-def]
            seen.append(len(ship.pinned))
            return super().send(batch)

    ship = shipper(spool, Watcher())
    ship.run_once()
    assert seen == [1]
    assert ship.pinned == set()


def test_the_metrics_count_the_pass(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 3)
    metrics = Registry()
    shipper(spool, MemoryTransport(), metrics=metrics).run_once()
    snapshot = metrics.snapshot()
    assert snapshot["ship_batches"] == 1
    assert snapshot["ship_records"] == 3
    assert snapshot["ship_segments_retired"] == 1


def test_a_damaged_segment_is_skipped_not_fatal(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 3)
    victim = next(iter(spool.glob("*.spool")))
    victim.write_bytes(b"nonsense")
    report = shipper(spool, MemoryTransport()).run_once()
    assert report.batches_sent == 0


def test_keeping_acknowledged_segments_is_possible(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 3)
    shipper(spool, MemoryTransport(), delete_acknowledged=False).run_once()
    assert list(spool.glob("*.spool"))


def test_the_backoff_recovers_after_a_success(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 3)
    transport = MemoryTransport(script=[Response(Outcome.RETRYABLE, 503)])
    ship = shipper(spool, transport, backoff=Backoff(policy=BackoffPolicy(), rng=random.Random(1)))
    ship.run_once()
    assert ship.backoff.failures == 1
    ship.run_once()
    assert ship.backoff.failures == 0


@pytest.mark.parametrize("count", [1, 5, 20])
def test_every_record_reaches_the_collector_once(tmp_path: Path, count: int) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", count)
    transport = MemoryTransport()
    shipper(spool, transport, limits=BatchLimits(max_records=3)).run_once()
    messages = [item["message"] for batch in transport.received for item in batch.records]
    assert sorted(messages) == sorted(f"line {index}" for index in range(count))


def test_a_fresh_meter_rolls_on_the_first_pass(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 2)
    ship = shipper(spool, MemoryTransport())
    ship.run_once()
    assert ship.meter.rollovers == 1


def test_a_meter_already_in_its_period_is_not_reset(tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    fill(spool, "pump-bridge", 2)
    clock = ManualClock()
    clock.set_wall("2025-03-04T12:00:00Z")
    ship = shipper(spool, MemoryTransport(), meter=metered(100_000, 400), clock=clock)
    ship.run_once()
    assert ship.meter.used_bytes > 400
