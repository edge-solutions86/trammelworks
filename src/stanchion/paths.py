"""Working out what a log file is from where it sits.

A site controller can present three layouts and which one it is depends on how
the box was commissioned, not on anything we control:

* CRI, the layout the newer image uses::

      /var/log/pods/<namespace>_<pod>_<uid>/<container>/<attempt>.log

* the flat symlink directory the same runtime maintains beside it::

      /var/log/containers/<pod>_<namespace>_<container>-<containerid>.log

* docker json-file, still on the 2019 estate::

      /var/lib/docker/containers/<containerid>/<containerid>-json.log

The attempt number is the restart count and it only appears in the CRI layout.
Everywhere else it has to be recovered from somewhere else, which is why the
reader keeps it separate from the unit name rather than folding the two into one
string the way the runtime's own filenames do.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import StrEnum
from pathlib import PurePosixPath

from stanchion.errors import ParseError
from stanchion.identity import parse_unit

CRI_POD_DIR = re.compile(r"^(?P<namespace>[^_]+)_(?P<pod>[^_]+)_(?P<uid>[0-9a-f-]{8,})$")
CRI_FILE = re.compile(r"^(?P<attempt>\d+)\.log(?P<rotation>(?:\.\d+)?)$")
FLAT_FILE = re.compile(
    r"^(?P<pod>[^_]+)_(?P<namespace>[^_]+)_(?P<container>.+)-(?P<cid>[0-9a-f]{64})\.log$"
)
DOCKER_FILE = re.compile(r"^(?P<cid>[0-9a-f]{64})-json\.log(?P<rotation>(?:\.\d+)?)$")

COMPRESSED_SUFFIXES = (".gz", ".zst")


class Layout(StrEnum):
    """Which of the three on disk conventions a file follows."""

    CRI = "cri"
    FLAT = "flat"
    DOCKER = "docker"


@dataclass(frozen=True, slots=True)
class LogPathInfo:
    """What a path says about the container that wrote it."""

    path: str
    layout: Layout
    unit: str
    attempt: int | None = None
    container_id: str | None = None
    namespace: str | None = None
    pod: str | None = None
    rotation: int = 0
    compressed: bool = False

    @property
    def is_current(self) -> bool:
        """True for the file the runtime is writing to right now."""
        return self.rotation == 0 and not self.compressed


def _strip_compression(name: str) -> tuple[str, bool]:
    for suffix in COMPRESSED_SUFFIXES:
        if name.endswith(suffix):
            return name[: -len(suffix)], True
    return name, False


def _rotation_index(raw: str) -> int:
    return int(raw.lstrip(".")) if raw else 0


def parse_log_path(path: str | PurePosixPath) -> LogPathInfo:
    """Classify a container log path, or say why it cannot be classified."""
    target = PurePosixPath(path)
    name, compressed = _strip_compression(target.name)
    parent = target.parent

    docker = DOCKER_FILE.match(name)
    if docker is not None:
        cid = docker.group("cid")
        if parent.name != cid:
            raise ParseError("docker log is not in its own container directory", source=str(target))
        return LogPathInfo(
            path=str(target),
            layout=Layout.DOCKER,
            unit=parse_unit_from_docker(cid),
            container_id=cid,
            rotation=_rotation_index(docker.group("rotation")),
            compressed=compressed,
        )

    flat = FLAT_FILE.match(name)
    if flat is not None:
        return LogPathInfo(
            path=str(target),
            layout=Layout.FLAT,
            unit=parse_unit(flat.group("container")),
            container_id=flat.group("cid"),
            namespace=flat.group("namespace"),
            pod=flat.group("pod"),
            compressed=compressed,
        )

    cri = CRI_FILE.match(name)
    if cri is not None:
        pod_dir = CRI_POD_DIR.match(parent.parent.name)
        if pod_dir is None:
            raise ParseError("cri log has no pod directory", source=str(target))
        return LogPathInfo(
            path=str(target),
            layout=Layout.CRI,
            unit=parse_unit(parent.name),
            attempt=int(cri.group("attempt")),
            namespace=pod_dir.group("namespace"),
            pod=pod_dir.group("pod"),
            rotation=_rotation_index(cri.group("rotation")),
            compressed=compressed,
        )

    raise ParseError("unrecognised container log path", source=str(target))


def parse_unit_from_docker(container_id: str) -> str:
    """Docker json-file paths carry no name, so the short id stands in.

    The compose project name would be better and the reader fills it in later
    from the runtime's own metadata. Until then a caller gets something stable
    and greppable rather than an empty string.
    """
    return f"c{container_id[:12]}"


def rotation_order(info: LogPathInfo) -> tuple[int, int]:
    """Sort key that reads a rotated set oldest first.

    Rotation numbers count backwards in time: ``x.log.2`` is older than
    ``x.log.1``, which is older than ``x.log``. Compressed files are older still
    because compression happens after the next rotation.
    """
    return (-info.rotation, 0 if info.compressed else 1)
