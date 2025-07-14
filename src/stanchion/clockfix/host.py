"""Asking the box which boot this is.

The kernel exposes a boot id at ``/proc/sys/kernel/random/boot_id``, a fresh
random uuid per boot. Reading it is the cheap, correct answer and it is the one
used everywhere it works.

It does not always work. The aggregator is developed and its corpus replayed on
machines that are not Linux, and there are hardened images in the estate where
``/proc/sys`` is not mounted into the container. Falling over there would mean
no clock correction at all, which is worse than a slightly weaker identifier, so
there is a fallback: a random id generated once and written beside the spool.

The fallback is deliberately *not* derived from the hostname or the machine id.
Those survive a reboot, and an identifier that survives a reboot would let
monotonic readings from two different boots be compared, which is exactly the
mistake boot ids exist to prevent.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from pathlib import Path

from stanchion.clockfix.session import parse_boot_id
from stanchion.errors import ConfigError

PROC_BOOT_ID = "/proc/sys/kernel/random/boot_id"
FALLBACK_FILENAME = "boot_id"


@dataclass(frozen=True, slots=True)
class BootIdentity:
    """The boot id and where it came from."""

    boot_id: str
    source: str

    @property
    def from_kernel(self) -> bool:
        return self.source == "kernel"


def read_kernel_boot_id(path: str | Path = PROC_BOOT_ID) -> str | None:
    """Read the kernel's boot id, or None where there is not one."""
    try:
        raw = Path(path).read_text(encoding="ascii")
    except (OSError, UnicodeDecodeError):
        return None
    try:
        return parse_boot_id(raw)
    except ConfigError:
        return None


def read_fallback_boot_id(directory: str | Path) -> str | None:
    """Read the id this process wrote for itself last time, if it is still valid."""
    target = Path(directory) / FALLBACK_FILENAME
    try:
        raw = target.read_text(encoding="ascii")
    except OSError:
        return None
    try:
        return parse_boot_id(raw)
    except ConfigError:
        return None


def write_fallback_boot_id(directory: str | Path) -> str:
    """Mint and store a new id for this run."""
    holder = Path(directory)
    holder.mkdir(parents=True, exist_ok=True)
    minted = uuid.uuid4().hex
    (holder / FALLBACK_FILENAME).write_text(minted, encoding="ascii", newline="\n")
    return minted


def current_boot_identity(
    state_directory: str | Path,
    proc_path: str | Path = PROC_BOOT_ID,
    reuse_fallback: bool = False,
) -> BootIdentity:
    """Work out the boot id for this run.

    ``reuse_fallback`` exists for the replay tool, which runs several passes over
    one recorded corpus and needs them all to agree on the session. A live site
    never sets it: a new process there means the box rebooted, and pretending
    otherwise would splice two boots into one session.
    """
    from_kernel = read_kernel_boot_id(proc_path)
    if from_kernel is not None:
        return BootIdentity(boot_id=from_kernel, source="kernel")
    if reuse_fallback:
        stored = read_fallback_boot_id(state_directory)
        if stored is not None:
            return BootIdentity(boot_id=stored, source="fallback-reused")
    return BootIdentity(boot_id=write_fallback_boot_id(state_directory), source="fallback")
