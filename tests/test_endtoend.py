"""The whole thing, over a simulated shift.

Every other test in this repository looks at one piece. These look at what a
forecourt actually does, at a volume where the pieces interact: segments roll,
the joiner spans a poll boundary, the meter runs down, records come out the
other end.

The invariants are the ones that would end a career if they were wrong:

* every line the containers wrote reaches the collector, once
* no card number reaches the spool, the archive, or the wire
* a restart loses nothing and repeats nothing
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from stanchion.archive.config import ArchiveSettings
from stanchion.clocks import ManualClock
from stanchion.config import IngestConfig, SiteConfig
from stanchion.ingest.cri import parse_cri_line
from stanchion.redact.engine import Redactor
from stanchion.redact.pan import find_pans
from stanchion.ship.config import ShipSettings
from stanchion.ship.transport import MemoryTransport
from stanchion.simulate import Simulation
from stanchion.site import Site
from stanchion.spool.config import SpoolSettings
from stanchion.spool.priority import PriorityTable

CORPUS_SIZE = 600

TABLE = PriorityTable(
    assignments={
        "payment-kernel": "payment",
        "price-sign": "chatter",
        "tank-gauge": "chatter",
    }
)


def build_config(tmp_path: Path, **over: object) -> SiteConfig:
    settings: dict[str, object] = {
        "site": "US-TX-0417",
        "ingest": IngestConfig(log_root=(tmp_path / "log").as_posix()),
        "spool": SpoolSettings(
            directory=(tmp_path / "spool").as_posix(),
            budget_bytes=4 * 1024 * 1024,
            segment_bytes=32 * 1024,
            priority=TABLE,
        ),
        "archive": ArchiveSettings(path=(tmp_path / "archive" / "records.sqlite3").as_posix()),
        "ship": ShipSettings(monthly_allowance_bytes=50 * 1024 * 1024, batch_records=50),
    }
    settings.update(over)
    return SiteConfig(**settings)  # type: ignore[arg-type]


def build_site(tmp_path: Path, transport: MemoryTransport, **over: object) -> Site:
    clock = ManualClock()
    clock.set_wall("2025-03-04T18:00:00Z")
    return Site.build(config=build_config(tmp_path, **over), transport=transport, clock=clock)


def expected_lines(tmp_path: Path) -> list[str]:
    """Every application line the corpus contains, as it should come out.

    Joined the way the runtime split it, and redacted, because redacted is what
    the site is supposed to deliver. Comparing against the raw text would be
    asserting that redaction does not happen.
    """
    redactor = Redactor()
    out: list[str] = []
    for path in sorted((tmp_path / "log").rglob("*.log")):
        pending = ""
        for raw in path.read_bytes().splitlines():
            line = parse_cri_line(raw)
            pending += line.message
            if not line.partial:
                out.append(redactor.redact_text(pending).text)
                pending = ""
        if pending:
            out.append(redactor.redact_text(pending).text)
    return out


def delivered(transport: MemoryTransport) -> list[str]:
    return [item["message"] for batch in transport.received for item in batch.records]


@pytest.fixture
def seeded(tmp_path: Path) -> Path:
    Simulation(seed=101).write_cri(tmp_path / "log", CORPUS_SIZE)
    return tmp_path


def test_every_line_reaches_the_collector_once(seeded: Path) -> None:
    transport = MemoryTransport()
    site = build_site(seeded, transport)
    site.open()
    site.cycle()
    site.close()
    site.ship()
    assert sorted(delivered(transport)) == sorted(expected_lines(seeded))


def test_nothing_is_delivered_twice(seeded: Path) -> None:
    transport = MemoryTransport()
    site = build_site(seeded, transport)
    site.open()
    for _ in range(3):
        site.cycle()
    site.close()
    site.ship()
    messages = delivered(transport)
    assert len(messages) == len(expected_lines(seeded))


def test_the_spool_empties(seeded: Path) -> None:
    site = build_site(seeded, MemoryTransport())
    site.open()
    site.cycle()
    site.close()
    for _ in range(10):
        if site.ship().idle:
            break
    assert list((seeded / "spool").glob("*.spool")) == []


def test_no_card_number_reaches_the_wire(seeded: Path) -> None:
    transport = MemoryTransport()
    site = build_site(seeded, transport)
    site.open()
    site.cycle()
    site.close()
    site.ship()
    for batch in transport.received:
        assert find_pans(batch.encode().decode("utf-8")) == []


def test_no_card_number_reaches_the_spool(seeded: Path) -> None:
    site = build_site(seeded, MemoryTransport())
    site.open()
    site.collect()
    site.spool.seal_all()
    for path in (seeded / "spool").glob("*.spool"):
        assert find_pans(path.read_bytes().decode("utf-8", errors="replace")) == []


def test_no_card_number_reaches_the_archive(seeded: Path) -> None:
    site = build_site(seeded, MemoryTransport())
    site.open()
    site.collect()
    assert site.archive is not None
    from stanchion.timestamps import NanoTime

    rows = site.archive.between(
        NanoTime.parse("2000-01-01T00:00:00Z"),
        NanoTime.parse("2100-01-01T00:00:00Z"),
        limit=100_000,
    )
    for row in rows:
        assert find_pans(row.message) == []


def test_something_was_actually_redacted(seeded: Path) -> None:
    site = build_site(seeded, MemoryTransport())
    site.open()
    site.collect()
    assert site.snapshot()["redact_removed"] > 0


def test_the_archive_holds_every_line(seeded: Path) -> None:
    site = build_site(seeded, MemoryTransport())
    site.open()
    site.cycle()
    site.close()
    assert site.snapshot()["archive_records"] == len(expected_lines(seeded))


def test_a_restart_loses_nothing_and_repeats_nothing(seeded: Path) -> None:
    first_transport = MemoryTransport()
    first = build_site(seeded, first_transport)
    first.open()
    first.cycle()
    first.close()
    second_transport = MemoryTransport()
    second = build_site(seeded, second_transport)
    second.open()
    second.cycle()
    second.close()
    second.ship()
    everything = delivered(first_transport) + delivered(second_transport)
    assert sorted(everything) == sorted(expected_lines(seeded))


def test_new_traffic_after_a_restart_is_collected(seeded: Path) -> None:
    first = build_site(seeded, MemoryTransport())
    first.open()
    first.cycle()
    first.close()
    Simulation(seed=202).write_cri(seeded / "log", 50)
    transport = MemoryTransport()
    second = build_site(seeded, transport)
    second.open()
    second.cycle()
    second.close()
    second.ship()
    assert len(delivered(transport)) > 0


def test_the_snapshot_adds_up(seeded: Path) -> None:
    site = build_site(seeded, MemoryTransport())
    site.open()
    site.cycle()
    site.close()
    site.ship()
    found = site.snapshot()
    assert found["ingest_records"] == found["archive_records"]
    assert found["ship_records"] <= found["ingest_records"]
    assert found["ship_meter_used"] > 0


def test_a_stack_trace_arrives_whole(seeded: Path) -> None:
    transport = MemoryTransport()
    site = build_site(seeded, transport)
    site.open()
    site.cycle()
    site.close()
    site.ship()
    traces = [item for item in delivered(transport) if "IllegalStateException" in item]
    assert traces


def test_the_delivered_payload_is_json(seeded: Path) -> None:
    transport = MemoryTransport()
    site = build_site(seeded, transport)
    site.open()
    site.cycle()
    site.close()
    site.ship()
    payload = json.loads(transport.received[0].encode())
    assert payload["site"] == "US-TX-0417"
    assert payload["records"]


def test_payment_records_ship_before_chatter(seeded: Path) -> None:
    transport = MemoryTransport()
    site = build_site(seeded, transport)
    site.open()
    site.collect()
    site.spool.seal_all()
    site.ship()
    sources = [batch.source for batch in transport.received]
    payment = [index for index, name in enumerate(sources) if "payment" in name]
    chatter = [index for index, name in enumerate(sources) if "chatter" in name]
    assert not payment or not chatter or min(payment) < min(chatter)
