"""Finding the files worth following.

The log root on a commissioned box holds more than the containers we care about.
There are the runtime's own files, there are rotated copies of everything, and on
a box that has been upgraded there is a second layout left behind by the previous
runtime holding the same containers under different names.

Discovery therefore does three things and keeps them apart:

* walk the root and classify every path it can
* drop what is not a live container log, saying why
* collapse the duplicates a two layout box presents, preferring the layout the
  running runtime writes

The unit filter in config is applied last. A site that only wants payment traffic
shipped still needs the other units discovered, because the counts in the nightly
summary are how anyone notices that the tank gauge stopped logging at all.
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path

from stanchion.errors import ParseError
from stanchion.paths import Layout, LogPathInfo, parse_log_path

LAYOUT_PREFERENCE = (Layout.CRI, Layout.FLAT, Layout.DOCKER)


class SkipReason(StrEnum):
    """Why a path in the log root is not being followed."""

    UNPARSEABLE = "unparseable"
    ROTATED = "rotated"
    COMPRESSED = "compressed"
    DUPLICATE = "duplicate"
    NOT_SELECTED = "not_selected"
    EMPTY = "empty"


@dataclass(frozen=True, slots=True)
class Skipped:
    path: str
    reason: SkipReason


@dataclass(slots=True)
class Discovery:
    """What one walk of the log root found."""

    follow: list[LogPathInfo] = field(default_factory=list)
    skipped: list[Skipped] = field(default_factory=list)

    @property
    def units(self) -> tuple[str, ...]:
        return tuple(sorted({info.unit for info in self.follow}))

    def reasons(self) -> dict[SkipReason, int]:
        counts: dict[SkipReason, int] = {}
        for entry in self.skipped:
            counts[entry.reason] = counts.get(entry.reason, 0) + 1
        return counts


def walk(root: Path) -> Iterator[Path]:
    """Every regular file under the root, in a stable order."""
    if not root.exists():
        return
    for path in sorted(root.rglob("*")):
        if path.is_file():
            yield path


def classify(paths: list[Path]) -> tuple[list[LogPathInfo], list[Skipped]]:
    """Split found paths into candidates and the ones to leave alone."""
    candidates: list[LogPathInfo] = []
    skipped: list[Skipped] = []
    for path in paths:
        try:
            info = parse_log_path(path.as_posix())
        except ParseError:
            skipped.append(Skipped(path=path.as_posix(), reason=SkipReason.UNPARSEABLE))
            continue
        if info.compressed:
            skipped.append(Skipped(path=info.path, reason=SkipReason.COMPRESSED))
            continue
        if info.rotation:
            skipped.append(Skipped(path=info.path, reason=SkipReason.ROTATED))
            continue
        candidates.append(info)
    return candidates, skipped


def _rank(info: LogPathInfo) -> tuple[int, int]:
    """Prefer the layout the running runtime writes, then the later attempt."""
    return (LAYOUT_PREFERENCE.index(info.layout), -(info.attempt or 0))


def deduplicate(candidates: list[LogPathInfo]) -> tuple[list[LogPathInfo], list[Skipped]]:
    """Keep one file per unit when two layouts describe the same container."""
    best: dict[str, LogPathInfo] = {}
    losers: list[Skipped] = []
    for info in candidates:
        current = best.get(info.unit)
        if current is None:
            best[info.unit] = info
            continue
        if _rank(info) < _rank(current):
            best[info.unit] = info
            losers.append(Skipped(path=current.path, reason=SkipReason.DUPLICATE))
        else:
            losers.append(Skipped(path=info.path, reason=SkipReason.DUPLICATE))
    return [best[unit] for unit in sorted(best)], losers


def discover(root: Path, units: tuple[str, ...] = ()) -> Discovery:
    """Walk the log root and decide what to follow."""
    candidates, skipped = classify(list(walk(root)))
    kept, duplicates = deduplicate(candidates)
    skipped.extend(duplicates)
    if units:
        wanted = set(units)
        selected = []
        for info in kept:
            if info.unit in wanted:
                selected.append(info)
            else:
                skipped.append(Skipped(path=info.path, reason=SkipReason.NOT_SELECTED))
        kept = selected
    return Discovery(follow=kept, skipped=skipped)
