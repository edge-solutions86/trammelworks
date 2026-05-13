"""Keeping the walk inside the log root it was pointed at.

The container runtime writes real files under the log root, and this reads them.
That is the whole arrangement, and it holds right up until something under the
root is a symlink pointing somewhere else.

That is not a hypothetical on these boxes. The two runtime layouts a site can
carry are linked together: the CRI path is frequently a symlink to the docker
one, which is why discovery has a layout preference at all. So a link under the
root is normal and following it blindly is not, and the difference is where it
lands.

What goes wrong if nothing checks:

* a link to ``/etc/shadow`` gets read as a container log, fails to parse as one,
  and its bytes end up in the skipped diagnostics
* a link to a device or a fifo blocks the collector's read forever, which on an
  unattended box means the site silently stops shipping
* a link into another site's spool would put one forecourt's records under
  another one's site id

None of that needs an attacker. A commissioning engineer tidying disk space by
linking a big directory somewhere with room on it produces the first two by
accident, and that is the likeliest way any of this happens.

So every candidate is resolved and then checked to be under the root, and
anything that is not a regular file is refused whatever it resolves to. The root
itself is resolved once, because a root that is *itself* reached through a link
is fine and comparing an unresolved root against resolved children would reject
every file on such a box.
"""

from __future__ import annotations

import stat
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path

from stanchion.errors import IngestError


class Refusal(StrEnum):
    """Why a path under the root will not be read."""

    OUTSIDE = "outside the log root"
    NOT_A_FILE = "not a regular file"
    UNREADABLE = "cannot be resolved"
    LOOP = "symlink loop"


@dataclass(frozen=True, slots=True)
class Verdict:
    """What the check decided about one path."""

    path: Path
    resolved: Path | None = None
    refusal: Refusal | None = None

    @property
    def ok(self) -> bool:
        return self.refusal is None

    def __bool__(self) -> bool:
        return self.ok

    def __str__(self) -> str:
        if self.ok:
            return f"{self.path} ok"
        return f"{self.path} refused: {self.refusal}"


def resolve_root(root: Path) -> Path:
    """The root as the filesystem sees it, with links followed.

    Resolved once and reused. A root that is itself behind a symlink is an
    ordinary arrangement, and comparing resolved children against an
    unresolved root would refuse every file on such a box.
    """
    try:
        return root.resolve(strict=False)
    except OSError as exc:
        raise IngestError("log root cannot be resolved", detail=str(exc)) from exc


def within(root: Path, target: Path) -> bool:
    """Whether a resolved path sits under a resolved root."""
    if target == root:
        return True
    return root in target.parents


def check(path: Path, root: Path) -> Verdict:
    """Decide whether one path may be read as a container log.

    Everything here is decided on the resolved path. Checking the link and then
    opening the target is the gap the check exists to close.
    """
    try:
        resolved = path.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        # RuntimeError is what a symlink loop raises, and it is worth its own
        # answer: it says the tree is wrong rather than that a file is missing.
        reason = Refusal.LOOP if isinstance(exc, RuntimeError) else Refusal.UNREADABLE
        return Verdict(path=path, refusal=reason)
    if not within(root, resolved):
        return Verdict(path=path, resolved=resolved, refusal=Refusal.OUTSIDE)
    try:
        mode = resolved.stat().st_mode
    except OSError:
        return Verdict(path=path, resolved=resolved, refusal=Refusal.UNREADABLE)
    if not stat.S_ISREG(mode):
        # A fifo is the one that matters. Reading one never returns, and an
        # unattended site that stops shipping is worse than one that complains.
        return Verdict(path=path, resolved=resolved, refusal=Refusal.NOT_A_FILE)
    return Verdict(path=path, resolved=resolved)


@dataclass(slots=True)
class Confinement:
    """The check, with the root resolved once for a whole walk."""

    root: Path
    resolved_root: Path | None = None

    def prepare(self) -> Path:
        if self.resolved_root is None:
            self.resolved_root = resolve_root(Path(self.root))
        return self.resolved_root

    def check(self, path: Path) -> Verdict:
        return check(path, self.prepare())

    def keep(self, paths: list[Path]) -> tuple[list[Path], list[Verdict]]:
        """Split a walk into what may be read and what was refused."""
        kept: list[Path] = []
        refused: list[Verdict] = []
        for path in paths:
            verdict = self.check(path)
            if verdict.ok:
                kept.append(path)
            else:
                refused.append(verdict)
        return kept, refused
