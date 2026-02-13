"""Where our own diagnostics go, and how they stop growing.

The aggregator writes its own event log, and on a box whose whole point is that
nobody visits it, an unbounded log is a disk that fills in eighteen months and
takes the tills with it.

So the sink is capped and rotates itself. One rotation, not many: the previous
file is kept and older ones are not, because the useful window for our own
diagnostics is "what happened before it went wrong last night", and a site with
six generations of them is a site whose disk budget went somewhere unhelpful.

It does **not** write under the container log root. That would be read back in
on the next poll, producing a feedback loop that grows quadratically and looks,
from a distance, exactly like a busy site.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from stanchion.errors import StanchionError

DEFAULT_MAX_BYTES = 8 * 1024 * 1024
PREVIOUS_SUFFIX = ".1"


@dataclass(slots=True)
class RotatingSink:
    """A file-like sink that rolls itself over at a size."""

    path: Path
    max_bytes: int = DEFAULT_MAX_BYTES
    size: int = field(default=0, init=False)
    rotations: int = field(default=0, init=False)
    _handle: object | None = field(default=None, init=False, repr=False)

    def __post_init__(self) -> None:
        self.path = Path(self.path)
        if self.max_bytes < 1024:
            raise StanchionError("an event log cap below 1 KiB is not useful")

    @property
    def previous_path(self) -> Path:
        return self.path.with_name(self.path.name + PREVIOUS_SUFFIX)

    def _stream(self) -> object:
        if self._handle is None:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self._handle = self.path.open("a", encoding="utf-8", newline="\n")
            self.size = self.path.stat().st_size
        return self._handle

    def write(self, text: str) -> int:
        """Write one line, rotating first if it would not fit."""
        encoded = len(text.encode("utf-8"))
        if self.size and self.size + encoded > self.max_bytes:
            self.rotate()
        handle = self._stream()
        handle.write(text)  # type: ignore[attr-defined]
        self.size += encoded
        return encoded

    def flush(self) -> None:
        if self._handle is not None:
            self._handle.flush()  # type: ignore[attr-defined]

    def rotate(self) -> Path:
        """Move the current file aside, replacing any previous one."""
        self.close()
        if self.path.exists():
            os.replace(self.path, self.previous_path)
        self.rotations += 1
        self.size = 0
        return self.previous_path

    def close(self) -> None:
        if self._handle is not None:
            self._handle.flush()  # type: ignore[attr-defined]
            self._handle.close()  # type: ignore[attr-defined]
            self._handle = None

    @property
    def total_bytes(self) -> int:
        """Everything this sink is holding, current file and previous."""
        found = 0
        for path in (self.path, self.previous_path):
            if path.exists():
                found += path.stat().st_size
        return found


def check_outside(path: Path, log_root: str) -> None:
    """Refuse an event log that the collector would read back in."""
    from pathlib import PurePosixPath

    root = PurePosixPath(log_root)
    target = PurePosixPath(Path(path).as_posix())
    if root == target.parent or root in target.parents:
        raise StanchionError(
            "the event log must not live under the container log root", detail=str(path)
        )
