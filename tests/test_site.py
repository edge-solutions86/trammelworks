from __future__ import annotations

from pathlib import Path

import pytest

from stanchion.clocks import ManualClock
from stanchion.config import IngestConfig, SiteConfig
from stanchion.redact.config import RedactConfig
from stanchion.ship.config import ShipSettings
from stanchion.ship.transport import FileTransport, HttpTransport, MemoryTransport
from stanchion.site import Site, build_transport, state_directory
from stanchion.spool.config import SpoolSettings
from stanchion.spool.priority import PriorityTable
from stanchion.telemetry import Registry
from tests.support.logfiles import SiteTree, cri_full, cri_line

TS = "2025-03-04T12:00:00.000000000Z"
VISA = "4111111111111111"


def config_for(tree: SiteTree, tmp_path: Path, **over: object) -> SiteConfig:
    settings: dict[str, object] = {
        "site": "US-TX-0417",
        "ingest": IngestConfig(log_root=tree.root.as_posix()),
        "spool": SpoolSettings(
            directory=(tmp_path / "spool").as_posix(), budget_bytes=1_000_000, segment_bytes=4096
        ),
        "ship": ShipSettings(monthly_allowance_bytes=10_000_000),
    }
    settings.update(over)
    return SiteConfig(**settings)  # type: ignore[arg-type]


def build(tree: SiteTree, tmp_path: Path, transport: MemoryTransport, **over: object) -> Site:
    clock = ManualClock()
    clock.set_wall("2025-03-04T12:00:00Z")
    return Site.build(
        config=config_for(tree, tmp_path, **over),
        transport=transport,
        clock=clock,
        metrics=Registry(),
    )


def test_the_state_directory_sits_beside_the_spool() -> None:
    assert state_directory("/var/lib/stanchion/spool") == Path("/var/lib/stanchion/state")


def test_an_http_endpoint_builds_an_http_transport(tmp_path: Path, tree: SiteTree) -> None:
    config = config_for(tree, tmp_path, ship=ShipSettings(endpoint="https://c.invalid/v1"))
    assert isinstance(build_transport(config), HttpTransport)


def test_a_file_endpoint_builds_a_file_transport(tmp_path: Path, tree: SiteTree) -> None:
    outbox = (tmp_path / "outbox").as_posix()
    config = config_for(tree, tmp_path, ship=ShipSettings(endpoint=f"file://{outbox}"))
    assert isinstance(build_transport(config), FileTransport)


def test_a_line_travels_from_the_log_file_to_the_collector(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "nozzle 1 lifted")])
    transport = MemoryTransport()
    site = build(tree, tmp_path, transport)
    site.open()
    site.cycle()
    messages = [item["message"] for batch in transport.received for item in batch.records]
    assert messages == ["nozzle 1 lifted"]


def test_the_record_carries_the_site_and_unit(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    transport = MemoryTransport()
    site = build(tree, tmp_path, transport)
    site.open()
    site.cycle()
    record = transport.received[0].records[0]
    assert record["site"] == "US-TX-0417"
    assert record["unit"] == "pump-bridge"


def test_a_card_number_never_reaches_the_spool(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("payment-kernel", [cri_full(TS, f"auth pan={VISA}")])
    site = build(tree, tmp_path, MemoryTransport())
    site.open()
    site.collect()
    site.spool.seal_all()
    on_disk = b"".join(path.read_bytes() for path in (tmp_path / "spool").glob("*.spool"))
    assert VISA.encode() not in on_disk


def test_a_card_number_never_reaches_the_collector(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("payment-kernel", [cri_full(TS, f"auth pan={VISA}")])
    transport = MemoryTransport()
    site = build(tree, tmp_path, transport)
    site.open()
    site.cycle()
    assert VISA not in transport.received[0].encode().decode()
    assert "411111******1111" in transport.received[0].encode().decode()


def test_split_chunks_arrive_as_one_record(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri(
        "pump-bridge",
        [cri_line(TS, "stdout", "P", "first half "), cri_line(TS, "stdout", "F", "second half")],
    )
    transport = MemoryTransport()
    site = build(tree, tmp_path, transport)
    site.open()
    site.cycle()
    messages = [item["message"] for batch in transport.received for item in batch.records]
    assert messages == ["first half second half"]


def test_every_record_gets_a_clock_confidence(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    transport = MemoryTransport()
    site = build(tree, tmp_path, transport)
    site.open()
    site.cycle()
    assert transport.received[0].records[0]["clock_confidence"] == "unknown"


def test_a_chrony_line_is_seen_as_a_sync(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("chronyd", [cri_full(TS, "System clock wrong by -7.0 seconds")])
    site = build(tree, tmp_path, MemoryTransport())
    site.open()
    report = site.collect()
    assert report.read == 1


def test_the_collect_report_counts_what_it_moved(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one") + cri_full(TS, "two")])
    site = build(tree, tmp_path, MemoryTransport())
    site.open()
    report = site.collect()
    assert report.read == 2
    assert report.shipped_to_spool == 2
    assert report.redacted == 0


def test_a_redacted_record_is_counted(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("payment-kernel", [cri_full(TS, f"pan={VISA}")])
    site = build(tree, tmp_path, MemoryTransport())
    site.open()
    assert site.collect().redacted == 1


def test_a_quiet_site_is_idle(tree: SiteTree, tmp_path: Path) -> None:
    tree.root.mkdir(parents=True, exist_ok=True)
    site = build(tree, tmp_path, MemoryTransport())
    site.open()
    assert site.cycle().idle is True


def test_the_meter_is_charged_and_saved(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    site = build(tree, tmp_path, MemoryTransport())
    site.open()
    site.cycle()
    stored = site.meter_store.load(site.config.ship)
    assert stored.used_bytes > 0


def test_the_state_lands_beside_the_spool(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    site = build(tree, tmp_path, MemoryTransport())
    site.open()
    site.cycle()
    state = state_directory(site.config.spool.directory)
    assert (state / "meter.json").exists()
    assert (state / "acks.json").exists()
    assert (state / "clock.json").exists()


def test_the_state_is_not_inside_the_spool(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    site = build(tree, tmp_path, MemoryTransport())
    site.open()
    site.cycle()
    assert not (tmp_path / "spool" / "meter.json").exists()


def test_a_restart_does_not_ship_the_same_line_twice(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "only once")])
    first = MemoryTransport()
    site = build(tree, tmp_path, first)
    site.open()
    site.cycle()
    site.close()
    second = MemoryTransport()
    again = build(tree, tmp_path, second)
    again.open()
    again.cycle()
    assert second.received == []


def test_new_lines_after_a_restart_are_picked_up(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "before")])
    site = build(tree, tmp_path, MemoryTransport())
    site.open()
    site.cycle()
    site.close()
    tree.write_cri("pump-bridge", [cri_full(TS, "after")])
    transport = MemoryTransport()
    again = build(tree, tmp_path, transport)
    again.open()
    again.cycle()
    messages = [item["message"] for batch in transport.received for item in batch.records]
    assert messages == ["after"]


def test_close_flushes_an_open_line(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("pump-bridge", [cri_line(TS, "stdout", "P", "never finished")])
    site = build(tree, tmp_path, MemoryTransport())
    site.open()
    site.collect()
    assert site.close().read == 1


def test_the_priority_table_reaches_the_spool(tree: SiteTree, tmp_path: Path) -> None:
    table = PriorityTable(assignments={"payment-kernel": "payment"})
    spool = SpoolSettings(
        directory=(tmp_path / "spool").as_posix(),
        budget_bytes=1_000_000,
        segment_bytes=4096,
        priority=table,
    )
    tree.write_cri("payment-kernel", [cri_full(TS, "auth")])
    site = build(tree, tmp_path, MemoryTransport(), spool=spool)
    site.open()
    site.collect()
    site.spool.seal_all()
    assert any("payment" in path.name for path in (tmp_path / "spool").glob("*.spool"))


def test_a_lab_site_may_run_with_redaction_off(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("payment-kernel", [cri_full(TS, f"pan={VISA}")])
    config = config_for(tree, tmp_path, site="XX-LAB-0001", redact=RedactConfig(enabled=False))
    clock = ManualClock()
    site = Site.build(config=config, transport=MemoryTransport(), clock=clock)
    site.open()
    assert site.collect().read == 1


@pytest.mark.parametrize("count", [1, 7, 30])
def test_every_line_written_reaches_the_collector_once(
    tree: SiteTree, tmp_path: Path, count: int
) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, f"line {index}") for index in range(count)])
    transport = MemoryTransport()
    site = build(tree, tmp_path, transport)
    site.open()
    site.cycle()
    site.close()
    site.ship()
    messages = [item["message"] for batch in transport.received for item in batch.records]
    assert sorted(messages) == sorted(f"line {index}" for index in range(count))
