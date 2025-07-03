"""Keeping the session ledger across a restart.

The correction for a record written before the last sync depends on syncs that
may have happened days ago, so the ledger has to outlive the process. It is
small: a few sessions, a handful of syncs each, and it is written whole rather
than appended to, because a partial ledger is a wrong ledger rather than a
smaller one.

Old sessions are pruned on save. A boot from three weeks ago cannot help correct
anything still in the spool, since the spool never holds that much, and keeping
them means the file grows for the life of the box.
"""

from __future__ import annotations

import json
import os
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from stanchion.clockfix.session import BootSession, SessionLedger, SyncEvent
from stanchion.errors import ConfigError
from stanchion.timestamps import NanoTime

FORMAT_VERSION = 1
DEFAULT_KEEP_SESSIONS = 8
FILENAME = "clock.json"


def sync_to_dict(event: SyncEvent) -> dict[str, Any]:
    return {
        "boot_id": event.boot_id,
        "monotonic_ns": event.monotonic_ns,
        "before": event.before.isoformat(),
        "after": event.after.isoformat(),
        "source": event.source,
    }


def sync_from_dict(payload: dict[str, Any]) -> SyncEvent:
    try:
        return SyncEvent(
            boot_id=str(payload["boot_id"]),
            monotonic_ns=int(payload["monotonic_ns"]),
            before=NanoTime.parse(str(payload["before"])),
            after=NanoTime.parse(str(payload["after"])),
            source=str(payload.get("source", "ntp")),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise ConfigError("sync event is missing a field", detail=str(exc)) from exc


def session_to_dict(session: BootSession) -> dict[str, Any]:
    return {
        "boot_id": session.boot_id,
        "first_monotonic_ns": session.first_monotonic_ns,
        "last_monotonic_ns": session.last_monotonic_ns,
        "syncs": [sync_to_dict(event) for event in session.syncs],
    }


def session_from_dict(payload: dict[str, Any]) -> BootSession:
    try:
        session = BootSession(
            boot_id=str(payload["boot_id"]),
            first_monotonic_ns=int(payload.get("first_monotonic_ns", 0)),
            last_monotonic_ns=int(payload.get("last_monotonic_ns", 0)),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise ConfigError("boot session is missing a field", detail=str(exc)) from exc
    for raw in payload.get("syncs", []):
        session.add_sync(sync_from_dict(raw))
    return session


def ledger_to_dict(ledger: SessionLedger) -> dict[str, Any]:
    return {
        "version": FORMAT_VERSION,
        "order": list(ledger.order),
        "sessions": [session_to_dict(ledger.sessions[boot_id]) for boot_id in ledger.order],
    }


def ledger_from_dict(payload: dict[str, Any]) -> SessionLedger:
    version = payload.get("version")
    if version != FORMAT_VERSION:
        raise ConfigError("unknown clock ledger version", detail=str(version))
    ledger = SessionLedger()
    for raw in payload.get("sessions", []):
        session = session_from_dict(raw)
        ledger.sessions[session.boot_id] = session
        ledger.order.append(session.boot_id)
    stated = [str(item) for item in payload.get("order", [])]
    if stated and set(stated) == set(ledger.order):
        ledger.order = stated
    return ledger


@dataclass(slots=True)
class ClockStore:
    """One small JSON file holding every boot we still remember."""

    directory: Path
    keep_sessions: int = DEFAULT_KEEP_SESSIONS
    corrupt: int = 0

    def __post_init__(self) -> None:
        self.directory = Path(self.directory)
        if self.keep_sessions < 1:
            raise ConfigError("keep_sessions must be at least 1", detail=str(self.keep_sessions))

    @property
    def path(self) -> Path:
        return self.directory / FILENAME

    def load(self) -> SessionLedger:
        """Read the ledger, treating a damaged one as an empty one."""
        try:
            text = self.path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return SessionLedger()
        except OSError as exc:
            raise ConfigError("clock ledger unreadable", detail=str(exc)) from exc
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            self.corrupt += 1
            return SessionLedger()
        if not isinstance(payload, dict):
            self.corrupt += 1
            return SessionLedger()
        try:
            return ledger_from_dict(payload)
        except ConfigError:
            self.corrupt += 1
            return SessionLedger()

    def save(self, ledger: SessionLedger) -> Path:
        """Prune, then write the whole ledger atomically."""
        ledger.forget_all_but(self.keep_sessions)
        self.directory.mkdir(parents=True, exist_ok=True)
        handle, temporary = tempfile.mkstemp(dir=self.directory, suffix=".tmp")
        try:
            with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as stream:
                json.dump(ledger_to_dict(ledger), stream, sort_keys=True, indent=1)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        except BaseException:
            Path(temporary).unlink(missing_ok=True)
            raise
        return self.path
