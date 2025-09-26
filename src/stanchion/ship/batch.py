"""Cutting a segment into batches the uplink can carry.

Delivery is at least once, so the collector will see some batches twice. That is
not a bug to be engineered away over an LTE link that drops mid request; it is
the contract, and the job here is to make the duplicate recognisable.

A batch id is therefore derived from what is in the batch, not from a counter or
a clock. The same records cut the same way always produce the same id, so a
retry after a lost acknowledgement arrives with the id the collector already
has. A counter would produce a new id for the same records and the duplicate
would land as new data.

Two bounds cut a batch, and both are needed. Bytes, because the meter is sold in
bytes and a request has to fit inside what is left of the month. Records,
because a batch of forty thousand tank readings is small on the wire and still
takes the collector minutes to ingest, which times the request out and produces
the retry that the byte bound was supposed to avoid.
"""

from __future__ import annotations

import json
from collections.abc import Iterable, Iterator
from dataclasses import dataclass, field
from hashlib import blake2b
from typing import Any

from stanchion.errors import ShipError

DEFAULT_MAX_BYTES = 256 * 1024
DEFAULT_MAX_RECORDS = 2000

BATCH_ID_BYTES = 12


@dataclass(frozen=True, slots=True)
class Batch:
    """One request's worth of records."""

    site: str
    records: tuple[dict[str, Any], ...]
    source: str = ""
    first_index: int = 0

    def __post_init__(self) -> None:
        if not self.records:
            raise ShipError("a batch cannot be empty")

    @property
    def count(self) -> int:
        return len(self.records)

    @property
    def last_index(self) -> int:
        return self.first_index + self.count - 1

    @property
    def batch_id(self) -> str:
        """A name for this batch that only depends on what is in it."""
        digest = blake2b(digest_size=BATCH_ID_BYTES)
        digest.update(self.site.encode("utf-8"))
        digest.update(b"\x1f")
        digest.update(self.source.encode("utf-8"))
        digest.update(b"\x1f")
        for record in self.records:
            digest.update(_canonical(record))
            digest.update(b"\x1e")
        return digest.hexdigest()

    def payload(self) -> dict[str, Any]:
        """The body the collector receives."""
        return {
            "site": self.site,
            "batch_id": self.batch_id,
            "source": self.source,
            "count": self.count,
            "records": list(self.records),
        }

    def encode(self) -> bytes:
        return json.dumps(self.payload(), separators=(",", ":"), sort_keys=True).encode("utf-8")

    @property
    def wire_bytes(self) -> int:
        return len(self.encode())


def _canonical(record: dict[str, Any]) -> bytes:
    return json.dumps(record, separators=(",", ":"), sort_keys=True).encode("utf-8")


def record_size(record: dict[str, Any]) -> int:
    """What one record costs inside a batch, separator included."""
    return len(_canonical(record)) + 1


@dataclass(frozen=True, slots=True)
class BatchLimits:
    """What one request is allowed to carry."""

    max_bytes: int = DEFAULT_MAX_BYTES
    max_records: int = DEFAULT_MAX_RECORDS

    def __post_init__(self) -> None:
        if self.max_bytes <= 0:
            raise ShipError("batch max_bytes must be positive", detail=str(self.max_bytes))
        if self.max_records <= 0:
            raise ShipError("batch max_records must be positive", detail=str(self.max_records))


@dataclass(slots=True)
class BatchStats:
    """What a cut produced."""

    batches: int = 0
    records: int = 0
    oversized: int = 0
    bytes_planned: int = 0
    largest: int = field(default=0)


def cut_batches(
    site: str,
    records: Iterable[dict[str, Any]],
    limits: BatchLimits | None = None,
    source: str = "",
    stats: BatchStats | None = None,
) -> Iterator[Batch]:
    """Cut a stream of records into batches, in order.

    A single record larger than the byte bound still goes, on its own. Dropping
    it would lose the one line most likely to matter, since the reason a record
    is that big is usually a stack trace.
    """
    bounds = limits if limits is not None else BatchLimits()
    tally = stats if stats is not None else BatchStats()
    held: list[dict[str, Any]] = []
    size = 0
    index = 0
    for record in records:
        cost = record_size(record)
        if held and (size + cost > bounds.max_bytes or len(held) >= bounds.max_records):
            yield _emit(site, held, source, index - len(held), tally)
            held, size = [], 0
        if cost > bounds.max_bytes:
            tally.oversized += 1
        held.append(record)
        size += cost
        index += 1
    if held:
        yield _emit(site, held, source, index - len(held), tally)


def _emit(
    site: str,
    held: list[dict[str, Any]],
    source: str,
    first_index: int,
    stats: BatchStats,
) -> Batch:
    batch = Batch(site=site, records=tuple(held), source=source, first_index=first_index)
    stats.batches += 1
    stats.records += batch.count
    wire = batch.wire_bytes
    stats.bytes_planned += wire
    stats.largest = max(stats.largest, wire)
    return batch
