"""Builders that write log files in the shapes a site controller produces.

Written by hand rather than captured, because the interesting cases are the ones
a real box only produces under load: a line split at exactly the chunk boundary,
a rotation between two reads, a truncated final line after a power cut.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

CRI_CHUNK = 16 * 1024

POD_UID = "1f2e3d4c-aaaa-bbbb-cccc-1234567890ab"


def cri_line(timestamp: str, stream: str, tag: str, message: str) -> str:
    """One physical CRI line, terminator included."""
    return f"{timestamp} {stream} {tag} {message}\n"


def cri_full(timestamp: str, message: str, stream: str = "stdout") -> str:
    return cri_line(timestamp, stream, "F", message)


def cri_split(timestamp: str, message: str, at: int, stream: str = "stdout") -> str:
    """Render one application line as a partial chunk plus its final chunk."""
    head, tail = message[:at], message[at:]
    return cri_line(timestamp, stream, "P", head) + cri_line(timestamp, stream, "F", tail)


def docker_line(timestamp: str, message: str, stream: str = "stdout") -> str:
    """One json-file record. The runtime keeps the trailing newline in ``log``."""
    return json.dumps({"log": message + "\n", "stream": stream, "time": timestamp}) + "\n"


def journal_export(fields: dict[str, str]) -> str:
    """One journald export block, ended by a blank line."""
    body = "".join(f"{key}={value}\n" for key, value in fields.items())
    return body + "\n"


@dataclass(slots=True)
class SiteTree:
    """A temporary directory laid out the way a commissioned box is."""

    root: Path
    namespace: str = "forecourt"
    written: dict[str, int] = field(default_factory=dict)

    def cri_path(self, unit: str, attempt: int = 0) -> Path:
        pod_dir = self.root / "pods" / f"{self.namespace}_{unit}-0_{POD_UID}" / unit
        pod_dir.mkdir(parents=True, exist_ok=True)
        return pod_dir / f"{attempt}.log"

    def flat_path(self, unit: str, container_id: str) -> Path:
        flat = self.root / "containers"
        flat.mkdir(parents=True, exist_ok=True)
        return flat / f"{unit}-0_{self.namespace}_{unit}-{container_id}.log"

    def docker_path(self, container_id: str) -> Path:
        # The container directory sits straight under the root rather than under
        # a "docker" segment. Two sixty four character names in one path is
        # already close to the Windows limit, and the parser only cares that the
        # file lives in a directory named after its container.
        holder = self.root / container_id
        holder.mkdir(parents=True, exist_ok=True)
        return holder / f"{container_id}-json.log"

    def write(self, target: Path, text: str) -> Path:
        """Append text and remember how many bytes this tree has produced."""
        with target.open("a", encoding="utf-8", newline="") as handle:
            handle.write(text)
        self.written[str(target)] = target.stat().st_size
        return target

    def write_cri(self, unit: str, lines: list[str], attempt: int = 0) -> Path:
        return self.write(self.cri_path(unit, attempt), "".join(lines))

    def rotate(self, target: Path) -> Path:
        """Move a file aside the way logrotate does, leaving a fresh one."""
        rotated = target.with_name(target.name + ".1")
        if rotated.exists():
            rotated.unlink()
        target.rename(rotated)
        target.touch()
        return rotated

    def truncate(self, target: Path, keep: int) -> None:
        """Cut a file short, which is what a power cut leaves behind."""
        data = target.read_bytes()[:keep]
        target.write_bytes(data)
