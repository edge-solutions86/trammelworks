"""Writing a small JSON file so a power cut cannot leave half of one.

Four things on this box keep state in a small JSON file: the read checkpoints,
the clock ledger, the meter and the acknowledgement watermarks. All four had
grown their own copy of the same eleven lines, and all four had to get the same
three details right:

* write to a temporary file **in the same directory**, because ``os.replace`` is
  only atomic within a filesystem and ``/tmp`` is frequently a different one
* ``fsync`` before the rename, or the rename can land ahead of the bytes and a
  power cut leaves a file that exists and is empty
* remove the temporary file if anything goes wrong, or a site accumulates one
  per failed write until the inodes run out

Reading is the other half. A file that will not parse is treated as absent,
because there is nothing useful to do with a corrupt checkpoint except start
again, and the count of how often that happened is worth having.
"""

from __future__ import annotations

import json
import os
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from stanchion.errors import StanchionError

TEMP_SUFFIX = ".tmp"


def write_atomic(path: Path, payload: dict[str, Any], indent: int | None = None) -> Path:
    """Write a mapping as JSON, atomically, creating the directory as needed."""
    directory = path.parent
    directory.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(dir=directory, suffix=TEMP_SUFFIX)
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(payload, stream, sort_keys=True, indent=indent)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise
    return path


@dataclass(slots=True)
class ReadResult:
    """What a read found, and whether the file was usable."""

    payload: dict[str, Any] | None = None
    missing: bool = False
    corrupt: bool = False

    @property
    def usable(self) -> bool:
        return self.payload is not None


def read_json(path: Path, version: int | None = None) -> ReadResult:
    """Read a mapping, treating anything unusable as absent rather than fatal."""
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return ReadResult(missing=True)
    except OSError as exc:
        raise StanchionError("state file unreadable", detail=str(exc)) from exc
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return ReadResult(corrupt=True)
    if not isinstance(payload, dict):
        return ReadResult(corrupt=True)
    if version is not None and payload.get("version") != version:
        return ReadResult(corrupt=True)
    return ReadResult(payload=payload)


@dataclass(slots=True)
class JsonStore:
    """One small state file, with the corrupt count everybody wants."""

    path: Path
    version: int | None = None
    indent: int | None = None
    corrupt: int = field(default=0)

    def __post_init__(self) -> None:
        self.path = Path(self.path)

    def load(self) -> dict[str, Any] | None:
        """The stored mapping, or None when there is nothing usable."""
        found = read_json(self.path, self.version)
        if found.corrupt:
            self.corrupt += 1
        return found.payload

    def save(self, payload: dict[str, Any]) -> Path:
        body = dict(payload)
        if self.version is not None:
            body["version"] = self.version
        return write_atomic(self.path, body, indent=self.indent)

    def forget(self) -> bool:
        """Remove the file. Returns whether there was one."""
        if not self.path.exists():
            return False
        self.path.unlink()
        return True
