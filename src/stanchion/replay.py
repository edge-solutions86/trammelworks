"""Reading a directory of batches back, the way the estate does off a stick.

A site whose modem has been dead for a week gets visited. The engineer plugs in
a stick, the file transport's output directory gets copied onto it, and the
stick goes back to the office. What arrives is a pile of JSON files with
signatures beside them and no other context at all.

This is the half that reads that pile. It is deliberately not the shipper in
reverse: it never sends anything, it never touches a spool, and it answers one
question per file, which is whether this batch is what the site that claims to
have written it actually wrote.

Every failure is reported rather than raised. The whole point of walking a stick
is that some of it will be wrong, and a reader that stopped at the first bad
file would need an engineer to delete files one at a time to see the rest. What
comes back is a verdict per batch and a tally at the end.

The order is by batch id rather than by filename or by mtime. Copying a
directory does not preserve either reliably, and a report whose order changes
depending on which machine ran it is a report nobody can diff against the last
one.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from stanchion.errors import ShipError
from stanchion.ship.signing import SigningKey, parse_signature, verify
from stanchion.ship.transport import SIGNATURE_SUFFIX
from stanchion.timestamps import NanoTime

BATCH_SUFFIX = ".json"


@dataclass(frozen=True, slots=True)
class BatchFile:
    """One batch on the stick, and the signature file beside it if there is one."""

    path: Path
    signature_path: Path | None = None

    @property
    def signed(self) -> bool:
        return self.signature_path is not None


@dataclass(frozen=True, slots=True)
class Verdict:
    """What one batch turned out to be."""

    path: Path
    ok: bool
    site: str = ""
    batch_id: str = ""
    records: int = 0
    reason: str = ""

    def __bool__(self) -> bool:
        return self.ok

    def __str__(self) -> str:
        if self.ok:
            return f"{self.path.name} ok, {self.records} records from {self.site}"
        return f"{self.path.name} REFUSED: {self.reason}"


@dataclass(slots=True)
class ReplayReport:
    """What a whole directory turned out to be."""

    verdicts: list[Verdict] = field(default_factory=list)

    @property
    def accepted(self) -> list[Verdict]:
        return [item for item in self.verdicts if item.ok]

    @property
    def refused(self) -> list[Verdict]:
        return [item for item in self.verdicts if not item.ok]

    @property
    def records(self) -> int:
        return sum(item.records for item in self.accepted)

    @property
    def sites(self) -> tuple[str, ...]:
        return tuple(sorted({item.site for item in self.accepted if item.site}))

    @property
    def clean(self) -> bool:
        return bool(self.verdicts) and not self.refused

    def __str__(self) -> str:
        end = f", {len(self.refused)} refused" if self.refused else ""
        return (
            f"{len(self.accepted)} batches accepted, {self.records} records"
            f" from {len(self.sites)} sites{end}"
        )


def find_batches(directory: Path) -> list[BatchFile]:
    """Every batch file in a directory, with its signature if one is beside it."""
    if not directory.is_dir():
        raise ShipError("not a directory of batches", detail=str(directory))
    found: list[BatchFile] = []
    for path in sorted(directory.iterdir()):
        if not path.is_file() or path.suffix != BATCH_SUFFIX:
            continue
        beside = path.with_name(path.name + SIGNATURE_SUFFIX)
        found.append(BatchFile(path=path, signature_path=beside if beside.is_file() else None))
    return found


def read_payload(path: Path) -> dict[str, Any]:
    """The batch body, checked to be the shape a batch has."""
    try:
        raw = path.read_bytes()
    except OSError as exc:
        raise ShipError("batch file unreadable", detail=str(exc)) from exc
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ShipError("batch file is not json", detail=str(path)) from exc
    if not isinstance(payload, dict):
        raise ShipError("batch file is not an object", detail=str(path))
    for name in ("site", "batch_id", "records"):
        if name not in payload:
            raise ShipError(f"batch file has no {name}", detail=str(path))
    if not isinstance(payload["records"], list):
        raise ShipError("batch records are not a list", detail=str(path))
    return payload


def check_one(
    found: BatchFile,
    key: SigningKey | None,
    now: NanoTime,
    require_signature: bool = True,
) -> Verdict:
    """Decide whether one batch file may be taken as genuine."""
    try:
        payload = read_payload(found.path)
    except ShipError as exc:
        return Verdict(path=found.path, ok=False, reason=str(exc))
    site = str(payload["site"])
    batch_id = str(payload["batch_id"])
    count = len(payload["records"])
    if key is None:
        # Nothing to check against. Say so rather than reporting a pass: a
        # collector that treats unverifiable as verified has no signatures.
        if require_signature:
            return Verdict(
                path=found.path,
                ok=False,
                site=site,
                batch_id=batch_id,
                reason="no key to check the signature against",
            )
        return Verdict(path=found.path, ok=True, site=site, batch_id=batch_id, records=count)
    if found.signature_path is None:
        return Verdict(
            path=found.path,
            ok=False,
            site=site,
            batch_id=batch_id,
            reason="no signature beside it",
        )
    try:
        signature = parse_signature(found.signature_path.read_text(encoding="utf-8"))
    except (ShipError, OSError) as exc:
        return Verdict(path=found.path, ok=False, site=site, batch_id=batch_id, reason=str(exc))
    check = verify(key, signature, site, batch_id, found.path.read_bytes(), now)
    if not check:
        return Verdict(path=found.path, ok=False, site=site, batch_id=batch_id, reason=check.reason)
    return Verdict(path=found.path, ok=True, site=site, batch_id=batch_id, records=count)


def replay(
    directory: Path,
    key: SigningKey | None,
    now: NanoTime,
    require_signature: bool = True,
) -> ReplayReport:
    """Walk a directory of batches and say what each one is."""
    report = ReplayReport()
    for found in find_batches(directory):
        report.verdicts.append(check_one(found, key, now, require_signature))
    report.verdicts.sort(key=lambda item: (item.batch_id, item.path.name))
    return report


def records_of(paths: list[Path]) -> list[dict[str, Any]]:
    """Every record in a set of batch files, in file then record order.

    Used by the estate side to load a stick into its own store. Duplicates are
    left in: delivery is at least once and deciding what is a duplicate is the
    collector's job, done on the batch id it already has.
    """
    out: list[dict[str, Any]] = []
    for path in paths:
        payload = read_payload(path)
        for item in payload["records"]:
            if isinstance(item, dict):
                out.append(item)
    return out
