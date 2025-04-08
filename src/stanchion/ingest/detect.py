"""Deciding which format a file is in.

The path says what the runtime intended and the bytes say what it actually
wrote, and on a site that has been upgraded in place those two disagree. A box
migrated from docker to containerd keeps the old ``/var/lib/docker/containers``
tree, and the migration tool rewrites the files in CRI form without moving them.
Trusting the path there loses the whole file.

So content decides and the path is only a tie breaker for a sample too short to
be sure about. A disagreement is reported rather than smoothed over, because it
is the one signal that a site was upgraded and nobody wrote it down.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from enum import StrEnum

from stanchion.errors import ParseError
from stanchion.paths import Layout, parse_log_path

SAMPLE_BYTES = 8192

CRI_PREFIX = re.compile(
    rb"^\d{4}-\d{2}-\d{2}[Tt ]\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?\S* (stdout|stderr) "
)
JOURNAL_FIELD = re.compile(rb"^[A-Z_][A-Z0-9_]*(=|\n)")


class Format(StrEnum):
    """What a reader has to be pointed at."""

    CRI = "cri"
    JSONFILE = "jsonfile"
    JOURNAL = "journal"


@dataclass(frozen=True, slots=True)
class Detection:
    """What was decided and how much the two sources agreed."""

    format: Format
    from_content: bool
    path_layout: Layout | None = None

    @property
    def disagreed(self) -> bool:
        """True when the path implied a different format from the bytes."""
        if self.path_layout is None or not self.from_content:
            return False
        return EXPECTED_BY_LAYOUT[self.path_layout] is not self.format


EXPECTED_BY_LAYOUT: dict[Layout, Format] = {
    Layout.CRI: Format.CRI,
    Layout.FLAT: Format.CRI,
    Layout.DOCKER: Format.JSONFILE,
}


def first_line(sample: bytes) -> bytes:
    end = sample.find(b"\n")
    return sample if end == -1 else sample[:end]


def looks_like_jsonfile(line: bytes) -> bool:
    stripped = line.strip()
    if not stripped.startswith(b"{"):
        return False
    try:
        payload = json.loads(stripped.decode("utf-8", errors="replace"))
    except json.JSONDecodeError:
        return False
    return isinstance(payload, dict) and "log" in payload and "time" in payload


def looks_like_cri(line: bytes) -> bool:
    return CRI_PREFIX.match(line) is not None


def looks_like_journal(sample: bytes) -> bool:
    line = first_line(sample)
    if not JOURNAL_FIELD.match(line + b"\n"):
        return False
    return b"=" in line or b"\n" in sample


def sniff(sample: bytes) -> Format | None:
    """Decide from content alone, or return None if the sample says nothing."""
    line = first_line(sample)
    if not line.strip():
        return None
    if looks_like_jsonfile(line):
        return Format.JSONFILE
    if looks_like_cri(line):
        return Format.CRI
    if looks_like_journal(sample):
        return Format.JOURNAL
    return None


def detect(path: str, sample: bytes) -> Detection:
    """Decide the format of one file, preferring what the bytes say."""
    layout: Layout | None
    try:
        layout = parse_log_path(path).layout
    except ParseError:
        layout = None
    found = sniff(sample)
    if found is not None:
        return Detection(format=found, from_content=True, path_layout=layout)
    if layout is not None:
        return Detection(format=EXPECTED_BY_LAYOUT[layout], from_content=False, path_layout=layout)
    raise ParseError("cannot tell what format this file is", source=path)
