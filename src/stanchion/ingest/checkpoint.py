"""Remembering where the reader got to.

A site controller reboots whenever the forecourt loses power, which in some parts
of the estate is weekly. Without a checkpoint every reboot either replays the
whole log root, which blows the LTE allowance in an afternoon, or skips to the
end, which loses the reason the box rebooted.

The checkpoint stores the file identity as well as the offset, and both are
checked on resume. An offset alone is meaningless after a rotation: the new file
at that path is a different file, and seeking 40 MB into it either fails or lands
in the middle of a line that has nothing to do with the one we stopped at.

Writes are atomic. A checkpoint half written by a power cut is worse than none,
because it looks valid and points somewhere wrong, so the file is written beside
the target and renamed over it.
"""

from __future__ import annotations

import json
import os
import re
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from stanchion.errors import IngestError
from stanchion.ingest.tail import FileIdentity
from stanchion.timestamps import NanoTime

SUFFIX = ".ckpt"
KEY_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")
FORMAT_VERSION = 1


@dataclass(frozen=True, slots=True)
class Checkpoint:
    """Where one file was read up to."""

    key: str
    path: str
    device: int
    inode: int
    offset: int
    updated_at: NanoTime

    @property
    def identity(self) -> FileIdentity:
        return FileIdentity(device=self.device, inode=self.inode)

    def matches(self, identity: FileIdentity) -> bool:
        return self.identity == identity

    def as_dict(self) -> dict[str, Any]:
        return {
            "version": FORMAT_VERSION,
            "key": self.key,
            "path": self.path,
            "device": self.device,
            "inode": self.inode,
            "offset": self.offset,
            "updated_at": self.updated_at.isoformat(),
        }

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> Checkpoint:
        version = payload.get("version")
        if version != FORMAT_VERSION:
            raise IngestError("unknown checkpoint version", detail=str(version))
        try:
            return cls(
                key=str(payload["key"]),
                path=str(payload["path"]),
                device=int(payload["device"]),
                inode=int(payload["inode"]),
                offset=int(payload["offset"]),
                updated_at=NanoTime.parse(str(payload["updated_at"])),
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise IngestError("checkpoint is missing a field", detail=str(exc)) from exc


def check_key(key: str) -> str:
    """Keys become filenames, so they may not wander out of the directory."""
    if not KEY_PATTERN.match(key):
        raise IngestError("malformed checkpoint key", detail=key)
    return key


@dataclass(slots=True)
class CheckpointStore:
    """One small file per followed log file."""

    directory: Path
    corrupt: int = 0

    def __post_init__(self) -> None:
        self.directory = Path(self.directory)

    def _target(self, key: str) -> Path:
        return self.directory / f"{check_key(key)}{SUFFIX}"

    def load(self, key: str) -> Checkpoint | None:
        """Read a checkpoint, treating a damaged one as absent."""
        target = self._target(key)
        try:
            text = target.read_text(encoding="utf-8")
        except FileNotFoundError:
            return None
        except OSError as exc:
            raise IngestError("checkpoint unreadable", detail=str(exc)) from exc
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            self.corrupt += 1
            return None
        if not isinstance(payload, dict):
            self.corrupt += 1
            return None
        try:
            return Checkpoint.from_dict(payload)
        except IngestError:
            self.corrupt += 1
            return None

    def save(self, checkpoint: Checkpoint) -> Path:
        """Write a checkpoint atomically."""
        if checkpoint.offset < 0:
            raise IngestError("cannot checkpoint a negative offset")
        target = self._target(checkpoint.key)
        self.directory.mkdir(parents=True, exist_ok=True)
        handle, temporary = tempfile.mkstemp(dir=self.directory, suffix=".tmp")
        try:
            with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as stream:
                json.dump(checkpoint.as_dict(), stream, sort_keys=True)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, target)
        except BaseException:
            Path(temporary).unlink(missing_ok=True)
            raise
        return target

    def forget(self, key: str) -> bool:
        """Drop a checkpoint. Returns whether there was one."""
        target = self._target(key)
        if not target.exists():
            return False
        target.unlink()
        return True

    def keys(self) -> tuple[str, ...]:
        if not self.directory.exists():
            return ()
        return tuple(sorted(p.stem for p in self.directory.glob(f"*{SUFFIX}")))

    def sweep(self, live: set[str]) -> int:
        """Remove checkpoints for files nobody is following any more."""
        removed = 0
        for key in self.keys():
            if key not in live:
                removed += int(self.forget(key))
        return removed
