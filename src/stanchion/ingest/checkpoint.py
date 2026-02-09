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

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from stanchion.errors import IngestError
from stanchion.ingest.tail import FileIdentity
from stanchion.jsonstore import JsonStore
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

    def _store(self, key: str) -> JsonStore:
        return JsonStore(path=self._target(key), version=FORMAT_VERSION)

    def load(self, key: str) -> Checkpoint | None:
        """Read a checkpoint, treating a damaged one as absent."""
        store = self._store(key)
        payload = store.load()
        self.corrupt += store.corrupt
        if payload is None:
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
        return self._store(checkpoint.key).save(checkpoint.as_dict())

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
