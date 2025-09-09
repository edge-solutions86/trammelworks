"""Choosing what to delete when the spool is full.

Eviction is where the priority classes earn their keep, and the order is three
deep:

1. never a protected class, whatever the pressure
2. never a segment the shipper has pinned, because it is being uploaded right
   now and deleting it mid flight loses records that the collector has not
   acknowledged
3. otherwise lowest weight first, and oldest first inside a class

A plan is produced before anything is deleted, and it says whether it can free
what was asked for. That matters: a spool whose budget is full of protected
records cannot be brought back inside its budget by eviction, and the right
answer there is to stop accepting new records of the protected class rather than
to delete some of them. Silently freeing less than asked would let the caller
carry on writing into a spool that has no room.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from stanchion.errors import SpoolError
from stanchion.spool.priority import PriorityTable
from stanchion.spool.segment import SegmentInfo


@dataclass(frozen=True, slots=True)
class EvictionPlan:
    """What would be deleted, and whether it is enough."""

    victims: tuple[SegmentInfo, ...] = ()
    needed: int = 0
    protected_bytes: int = 0
    pinned_bytes: int = 0

    @property
    def freed(self) -> int:
        return sum(item.size for item in self.victims)

    @property
    def sufficient(self) -> bool:
        return self.freed >= self.needed

    @property
    def shortfall(self) -> int:
        return max(0, self.needed - self.freed)

    @property
    def empty(self) -> bool:
        return not self.victims


def plan_eviction(
    segments: list[SegmentInfo],
    table: PriorityTable,
    needed: int,
    pinned: frozenset[Path] = frozenset(),
) -> EvictionPlan:
    """Pick segments to delete, worst class and oldest first."""
    if needed < 0:
        raise SpoolError("cannot free a negative number of bytes", detail=str(needed))
    protected = table.protected_classes
    protected_bytes = sum(item.size for item in segments if item.klass in protected)
    pinned_bytes = sum(item.size for item in segments if item.path in pinned)
    if needed == 0:
        return EvictionPlan(needed=0, protected_bytes=protected_bytes, pinned_bytes=pinned_bytes)
    order = {name: index for index, name in enumerate(table.evict_order())}
    candidates = [item for item in segments if item.klass in order and item.path not in pinned]
    candidates.sort(key=lambda item: (order[item.klass], item.sequence))
    victims: list[SegmentInfo] = []
    freed = 0
    for item in candidates:
        if freed >= needed:
            break
        victims.append(item)
        freed += item.size
    return EvictionPlan(
        victims=tuple(victims),
        needed=needed,
        protected_bytes=protected_bytes,
        pinned_bytes=pinned_bytes,
    )


@dataclass(slots=True)
class EvictionResult:
    """What actually went."""

    deleted: list[SegmentInfo] = field(default_factory=list)
    freed: int = 0
    failed: list[tuple[SegmentInfo, str]] = field(default_factory=list)

    @property
    def count(self) -> int:
        return len(self.deleted)


def apply_eviction(plan: EvictionPlan) -> EvictionResult:
    """Delete the planned segments, reporting anything that would not go."""
    result = EvictionResult()
    for item in plan.victims:
        try:
            item.path.unlink()
        except FileNotFoundError:
            # Already gone. Counted as freed because the bytes are not there.
            result.deleted.append(item)
            result.freed += item.size
        except OSError as exc:
            result.failed.append((item, str(exc)))
            continue
        else:
            result.deleted.append(item)
            result.freed += item.size
    return result


def evictable_bytes(
    segments: list[SegmentInfo],
    table: PriorityTable,
    pinned: frozenset[Path] = frozenset(),
) -> int:
    """The most eviction could ever free, given what is protected and pinned."""
    order = set(table.evict_order())
    return sum(item.size for item in segments if item.klass in order and item.path not in pinned)
