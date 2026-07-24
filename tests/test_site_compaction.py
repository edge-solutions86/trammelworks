from __future__ import annotations

from pathlib import Path

import pytest

from stanchion.archive.config import ArchiveSettings
from stanchion.clocks import ManualClock
from stanchion.config import IngestConfig, SiteConfig
from stanchion.errors import ConfigError
from stanchion.records import LogRecord, Stream
from stanchion.ship.backoff import Outcome
from stanchion.ship.compact import COMPACT_SUFFIX
from stanchion.ship.config import ShipSettings, ship_settings_from_mapping
from stanchion.ship.transport import MemoryTransport, Response
from stanchion.site import Site
from stanchion.spool.config import SpoolSettings
from stanchion.spool.reader import read_segment
from stanchion.timestamps import NanoTime
from tests.support.logfiles import SiteTree

TS = "2025-03-04T12:00:00.000000000Z"


def config_for(tree: SiteTree, tmp_path: Path, **over: object) -> SiteConfig:
    ship: dict[str, object] = {"monthly_allowance_bytes": 10_000_000, "batch_records": 5}
    ship.update(over)
    return SiteConfig(
        site="US-TX-0417",
        ingest=IngestConfig(log_root=tree.root.as_posix()),
        spool=SpoolSettings(
            directory=(tmp_path / "spool").as_posix(),
            budget_bytes=4_000_000,
            segment_bytes=200_000,
            compress=False,
        ),
        ship=ShipSettings(**ship),  # type: ignore[arg-type]
        # Named rather than left to default, which is a real absolute path.
        archive=ArchiveSettings(path=(tmp_path / "archive" / "records.sqlite3").as_posix()),
    )


def build(tree: SiteTree, tmp_path: Path, transport: MemoryTransport, **over: object) -> Site:
    clock = ManualClock()
    clock.set_wall("2025-03-04T12:00:00Z")
    site = Site.build(config=config_for(tree, tmp_path, **over), transport=transport, clock=clock)
    site.open()
    return site


def stalling(after: int) -> MemoryTransport:
    """Acknowledges the first ``after`` batches, then stops answering."""
    return MemoryTransport(
        script=[Response(outcome=Outcome.OK, status=200) for _ in range(after)],
        default=Response(outcome=Outcome.RETRYABLE, status=503),
    )


def load(site: Site, count: int) -> None:
    site.absorb(
        [
            LogRecord(
                site="US-TX-0417",
                unit="pump-bridge",
                stream=Stream.STDOUT,
                observed_at=NanoTime.parse(TS),
                message=f"line {index:03d} " + "x" * 400,
            )
            for index in range(count)
        ]
    )


def only_segment(tmp_path: Path) -> Path:
    found = sorted((tmp_path / "spool").glob("*.spool"))
    assert len(found) == 1
    return found[0]


def messages(path: Path) -> list[str]:
    return [str(frame.payload["message"]) for frame in read_segment(path).frames]


def test_a_fully_acknowledged_segment_is_deleted_not_compacted(
    tree: SiteTree, tmp_path: Path
) -> None:
    site = build(tree, tmp_path, MemoryTransport())
    load(site, 20)
    site.ship()
    assert not list((tmp_path / "spool").glob("*.spool"))
    site.close()


def test_a_part_acknowledged_segment_shrinks(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, stalling(2), compact_min_reclaim_bytes=1)
    load(site, 30)
    site.spool.seal_all()
    before = only_segment(tmp_path).stat().st_size
    site.ship()
    assert only_segment(tmp_path).stat().st_size < before
    site.close()


def test_what_is_left_is_what_was_not_acknowledged(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, stalling(2), compact_min_reclaim_bytes=1)
    load(site, 30)
    site.spool.seal_all()
    site.ship()
    assert messages(only_segment(tmp_path))[0].startswith("line 010")
    site.close()


def test_the_watermark_matches_what_is_on_disk(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, stalling(2), compact_min_reclaim_bytes=1)
    load(site, 30)
    site.spool.seal_all()
    site.ship()
    segment = only_segment(tmp_path)
    mark = site.shipper.acks.marks[segment.name]
    assert mark.acked == 0
    assert mark.total == len(read_segment(segment).frames)
    site.close()


def test_the_rest_ships_on_the_next_pass(tree: SiteTree, tmp_path: Path) -> None:
    transport = stalling(2)
    site = build(tree, tmp_path, transport, compact_min_reclaim_bytes=1)
    load(site, 30)
    site.spool.seal_all()
    site.ship()
    transport.default = Response(outcome=Outcome.OK, status=200)
    site.shipper.backoff.succeed()
    site.ship()
    assert not list((tmp_path / "spool").glob("*.spool"))
    site.close()


def test_nothing_is_lost_across_a_compaction(tree: SiteTree, tmp_path: Path) -> None:
    transport = stalling(2)
    site = build(tree, tmp_path, transport, compact_min_reclaim_bytes=1)
    load(site, 30)
    site.spool.seal_all()
    site.ship()
    transport.default = Response(outcome=Outcome.OK, status=200)
    site.shipper.backoff.succeed()
    site.ship()
    sent = {item["message"] for batch in transport.received for item in batch.records}
    assert len(sent) == 30
    site.close()


def test_compaction_can_be_turned_off(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, stalling(2), compact=False)
    load(site, 30)
    site.spool.seal_all()
    before = only_segment(tmp_path).stat().st_size
    site.ship()
    assert only_segment(tmp_path).stat().st_size == before
    site.close()


def test_turning_it_off_gives_back_an_idle_result(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, MemoryTransport(), compact=False)
    assert site.compact().idle
    site.close()


def test_a_saving_under_both_floors_is_left_alone(tree: SiteTree, tmp_path: Path) -> None:
    # Both have to say no. Either one on its own is a reason to rewrite, which
    # is what lets a tiny segment be compacted on the fraction and a large one
    # on the byte count.
    site = build(
        tree,
        tmp_path,
        stalling(2),
        compact_min_reclaim_bytes=1024 * 1024,
        compact_min_fraction=0.9,
    )
    load(site, 30)
    site.spool.seal_all()
    before = only_segment(tmp_path).stat().st_size
    site.ship()
    assert only_segment(tmp_path).stat().st_size == before
    site.close()


def test_opening_clears_a_sidecar_a_crash_left(tree: SiteTree, tmp_path: Path) -> None:
    spool = tmp_path / "spool"
    spool.mkdir(parents=True)
    stray = spool / ("000000000001-payment.spool" + COMPACT_SUFFIX)
    stray.write_bytes(b"half written")
    site = build(tree, tmp_path, MemoryTransport())
    assert not stray.exists()
    site.close()


def test_the_counters_say_what_was_compacted(tree: SiteTree, tmp_path: Path) -> None:
    site = build(tree, tmp_path, stalling(2), compact_min_reclaim_bytes=1)
    load(site, 30)
    site.spool.seal_all()
    site.ship()
    assert site.snapshot()["spool_compacted_segments"] == 1
    site.close()


def test_a_negative_reclaim_is_refused() -> None:
    with pytest.raises(ConfigError, match="cannot be negative"):
        ShipSettings(compact_min_reclaim_bytes=-1)


def test_a_fraction_outside_the_range_is_refused() -> None:
    with pytest.raises(ConfigError, match="fraction of a segment"):
        ShipSettings(compact_min_fraction=1.5)


def test_the_compaction_keys_come_out_of_the_config_block() -> None:
    settings = ship_settings_from_mapping(
        {"compact": False, "compact_min_reclaim": "2MiB", "compact_min_fraction": 0.5}
    )
    assert settings.compact is False
    assert settings.compact_min_reclaim_bytes == 2 * 1024 * 1024
    assert settings.compact_min_fraction == 0.5


def test_an_unknown_compaction_key_is_refused() -> None:
    with pytest.raises(ConfigError, match="unknown ship key"):
        ship_settings_from_mapping({"compact_when": "tuesday"})


def test_a_saving_over_the_fraction_is_taken_even_under_the_byte_floor(
    tree: SiteTree, tmp_path: Path
) -> None:
    site = build(tree, tmp_path, stalling(2), compact_min_reclaim_bytes=1024 * 1024)
    load(site, 30)
    site.spool.seal_all()
    before = only_segment(tmp_path).stat().st_size
    site.ship()
    assert only_segment(tmp_path).stat().st_size < before
    site.close()
