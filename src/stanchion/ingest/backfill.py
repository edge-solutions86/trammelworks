"""Reading the rotated files nobody got to.

The tailer follows the live file. When a site has been down for a day, or the
process was stopped while a container was busy, the records that matter are in
the files logrotate moved aside: ``0.log.1``, ``0.log.2.gz`` and so on. Nothing
in the ordinary path will ever read them, because the tailer only knows about
the path the runtime writes to.

So backfill is a separate, deliberate operation. It is not run automatically and
that is the point: reading a day of rotated logs can put a gigabyte through the
spool, and on a metered link that is a decision somebody makes rather than one a
timer makes for them.

Order is oldest first, which for logrotate means highest suffix first, and
compressed before uncompressed at the same suffix. Reading them in directory
order would interleave two shifts.
"""

from __future__ import annotations

import gzip
from collections.abc import Iterator
from dataclasses import dataclass, field
from pathlib import Path

from stanchion.errors import IngestError, ParseError
from stanchion.ingest.detect import SAMPLE_BYTES, detect
from stanchion.ingest.join import Joiner, JoinLimits
from stanchion.ingest.readers import reader_for
from stanchion.paths import LogPathInfo, parse_log_path, rotation_order
from stanchion.records import LogRecord

COMPRESSED_SUFFIXES = (".gz",)
DEFAULT_MAX_BYTES = 64 * 1024 * 1024


def rotated_siblings(live: Path) -> list[LogPathInfo]:
    """Every rotated file beside a live one, oldest first."""
    found: list[LogPathInfo] = []
    for path in sorted(live.parent.iterdir()):
        if not path.is_file() or path == live:
            continue
        if not path.name.startswith(live.name):
            continue
        try:
            info = parse_log_path(path.as_posix())
        except ParseError:
            continue
        if info.rotation or info.compressed:
            found.append(info)
    return sorted(found, key=rotation_order)


def read_whole(path: Path, compressed: bool, max_bytes: int) -> bytes:
    """Read a rotated file, decompressing where it needs it."""
    try:
        if compressed:
            with gzip.open(path, "rb") as handle:
                data = handle.read(max_bytes + 1)
        else:
            data = path.read_bytes()
    except gzip.BadGzipFile as exc:
        # BadGzipFile is an OSError, so it has to be caught first or a file
        # that is simply not gzip reports as an unreadable one and the operator
        # goes looking at permissions.
        raise IngestError("rotated file is not gzip", detail=str(path)) from exc
    except EOFError as exc:
        raise IngestError("rotated file is truncated", detail=str(path)) from exc
    except OSError as exc:
        raise IngestError("rotated file unreadable", detail=str(exc)) from exc
    if len(data) > max_bytes:
        raise IngestError("rotated file is larger than the cap", detail=str(path))
    return data


@dataclass(slots=True)
class BackfillReport:
    """What one backfill pass found."""

    files: int = 0
    records: int = 0
    skipped: int = 0
    bytes_read: int = 0
    failed: list[str] = field(default_factory=list)

    def __str__(self) -> str:
        end = f", {len(self.failed)} unreadable" if self.failed else ""
        return f"backfilled {self.records} records from {self.files} files{end}"


@dataclass(slots=True)
class Backfiller:
    """Reads a unit's rotated files into records."""

    site: str
    info: LogPathInfo
    limits: JoinLimits = field(default_factory=JoinLimits)
    max_bytes: int = DEFAULT_MAX_BYTES
    report: BackfillReport = field(default_factory=BackfillReport)

    def sources(self) -> list[LogPathInfo]:
        return rotated_siblings(Path(self.info.path))

    def records(self) -> Iterator[LogRecord]:
        """Every record in the rotated files, oldest first.

        A fresh joiner per file, because a rotation is a hard boundary: a line
        cut across one was already lost when the runtime moved the file, and
        joining across would splice two unrelated halves into one record.
        """
        for source in self.sources():
            path = Path(source.path)
            try:
                data = read_whole(path, source.compressed, self.max_bytes)
            except IngestError:
                self.report.failed.append(source.path)
                continue
            self.report.files += 1
            self.report.bytes_read += len(data)
            yield from self._records_in(source, data)

    def _records_in(self, source: LogPathInfo, data: bytes) -> Iterator[LogRecord]:
        try:
            found = detect(source.path, data[:SAMPLE_BYTES])
        except ParseError:
            self.report.failed.append(source.path)
            return
        reader = reader_for(found.format)
        joiner = Joiner(site=self.site, unit=source.unit, limits=self.limits)
        parsed = reader.read(data, path=source.path)
        self.report.skipped += parsed.skipped
        for fragment in parsed.fragments:
            record = joiner.feed(fragment)
            if record is not None:
                self.report.records += 1
                yield record
        for record in joiner.flush():
            self.report.records += 1
            yield record
