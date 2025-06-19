"""Boot sessions, and the moments the clock was told the truth.

A forecourt controller has a battery backed RTC that is usually a few seconds
out and occasionally a few hours out, because the battery is eight years old and
nobody replaces one that has not stopped the till. NTP only lands when the LTE
modem is up, which on a rural site can be twice a day.

So the wall clock on a log line is a *claim*, and the two things that turn it
into evidence are here:

* a **boot session**, identified by the kernel's boot id. Inside one session the
  monotonic counter is trustworthy: it never jumps and never goes backwards, so
  the ordering and the intervals inside a session are facts even when the
  absolute times are wrong.
* a **sync event**, the moment NTP moved the wall clock. It records what the
  clock said and what it should have said, which is the only direct measurement
  of the error we ever get.

Nothing is corrected here. This module records what happened; the model built on
top of it decides what a given timestamp should have been.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from stanchion.errors import ConfigError
from stanchion.timestamps import NS_PER_SECOND, NanoTime

BOOT_ID_PATTERN = re.compile(r"^[0-9a-f]{32}$")

#: A correction larger than this is a clock that was wrong by more than a shift.
#: Still applied, still recorded, but it is worth a line in the summary.
LARGE_CORRECTION_NS = 3600 * NS_PER_SECOND


def parse_boot_id(raw: str) -> str:
    cleaned = raw.strip().lower().replace("-", "")
    if not BOOT_ID_PATTERN.match(cleaned):
        raise ConfigError("malformed boot id", detail=raw)
    return cleaned


@dataclass(frozen=True, slots=True)
class SyncEvent:
    """One moment the wall clock was corrected.

    ``before`` is what the clock read at that instant and ``after`` is what it
    was set to. The difference is the error the clock had accumulated, which is
    what every record written earlier in this session is carrying.
    """

    boot_id: str
    monotonic_ns: int
    before: NanoTime
    after: NanoTime
    source: str = "ntp"

    def __post_init__(self) -> None:
        object.__setattr__(self, "boot_id", parse_boot_id(self.boot_id))
        if self.monotonic_ns < 0:
            raise ConfigError("negative monotonic reading", detail=str(self.monotonic_ns))

    @property
    def offset_ns(self) -> int:
        """What to add to a clock reading to get the truth, at this instant."""
        return self.after - self.before

    @property
    def large(self) -> bool:
        return abs(self.offset_ns) >= LARGE_CORRECTION_NS

    @property
    def forwards(self) -> bool:
        """True when the clock was behind and had to be moved on."""
        return self.offset_ns > 0


@dataclass(slots=True)
class BootSession:
    """One run of the box, from power on to power off."""

    boot_id: str
    first_monotonic_ns: int = 0
    last_monotonic_ns: int = 0
    syncs: list[SyncEvent] = field(default_factory=list)

    def __post_init__(self) -> None:
        self.boot_id = parse_boot_id(self.boot_id)
        for event in self.syncs:
            self._check(event)
        self.syncs.sort(key=lambda event: event.monotonic_ns)

    def _check(self, event: SyncEvent) -> None:
        if event.boot_id != self.boot_id:
            raise ConfigError("sync event belongs to another boot", detail=event.boot_id)

    def observe(self, monotonic_ns: int) -> None:
        """Note that something happened at this point in the session."""
        if monotonic_ns < 0:
            raise ConfigError("negative monotonic reading", detail=str(monotonic_ns))
        if not self.last_monotonic_ns and not self.first_monotonic_ns:
            self.first_monotonic_ns = monotonic_ns
        self.first_monotonic_ns = min(self.first_monotonic_ns, monotonic_ns)
        self.last_monotonic_ns = max(self.last_monotonic_ns, monotonic_ns)

    def add_sync(self, event: SyncEvent) -> None:
        """Record a correction, keeping the list in monotonic order."""
        self._check(event)
        self.syncs.append(event)
        self.syncs.sort(key=lambda item: item.monotonic_ns)
        self.observe(event.monotonic_ns)

    @property
    def synced(self) -> bool:
        return bool(self.syncs)

    @property
    def duration_ns(self) -> int:
        return self.last_monotonic_ns - self.first_monotonic_ns

    def bracket(self, monotonic_ns: int) -> tuple[SyncEvent | None, SyncEvent | None]:
        """The syncs immediately before and after a point in the session."""
        before: SyncEvent | None = None
        after: SyncEvent | None = None
        for event in self.syncs:
            if event.monotonic_ns <= monotonic_ns:
                before = event
            elif after is None:
                after = event
        return before, after


@dataclass(slots=True)
class SessionLedger:
    """Every boot the box has had that we still know about."""

    sessions: dict[str, BootSession] = field(default_factory=dict)
    order: list[str] = field(default_factory=list)

    def session(self, boot_id: str) -> BootSession:
        """Get the session, creating it the first time this boot is seen."""
        cleaned = parse_boot_id(boot_id)
        found = self.sessions.get(cleaned)
        if found is None:
            found = BootSession(boot_id=cleaned)
            self.sessions[cleaned] = found
            self.order.append(cleaned)
        return found

    def add_sync(self, event: SyncEvent) -> None:
        self.session(event.boot_id).add_sync(event)

    @property
    def current(self) -> BootSession | None:
        """The most recently seen boot, which is the one running now."""
        if not self.order:
            return None
        return self.sessions[self.order[-1]]

    @property
    def reboots(self) -> int:
        """How many times the box has restarted inside what we remember."""
        return max(0, len(self.order) - 1)

    def forget_all_but(self, keep: int) -> int:
        """Drop the oldest sessions. Returns how many went."""
        if keep < 1:
            raise ConfigError("must keep at least one session", detail=str(keep))
        removed = 0
        while len(self.order) > keep:
            oldest = self.order.pop(0)
            del self.sessions[oldest]
            removed += 1
        return removed
