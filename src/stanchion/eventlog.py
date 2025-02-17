"""Our own diagnostic output.

An aggregator that writes unstructured chatter into the same directory it reads
from will eventually ingest itself, so this writes JSON lines to a separate file
and never to the container log root. The estate parses it, which means the field
set is fixed and unknown keys are a bug rather than an extension point.

Levels are deliberately coarse. On a box nobody logs into, the only question
that matters is whether a human has to drive out to the site.

The event name and level are positional only so that ``event``, ``level`` and
``at`` stay usable as field names. They read naturally in a payload and a caller
should not have to know which words the envelope has already taken.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass, field
from enum import IntEnum
from typing import Any, TextIO

from stanchion.clocks import Clock, SystemClock
from stanchion.errors import StanchionError

RESERVED_KEYS = frozenset({"at", "level", "event"})


class Level(IntEnum):
    """Coarse on purpose."""

    DEBUG = 10
    INFO = 20
    WARN = 30
    ERROR = 40

    @classmethod
    def parse(cls, raw: str) -> Level:
        try:
            return cls[raw.strip().upper()]
        except KeyError as exc:
            raise StanchionError(f"unknown level {raw!r}") from exc


@dataclass(slots=True)
class EventLog:
    """A JSON lines writer with a fixed envelope."""

    sink: TextIO | None = None
    minimum: Level = Level.INFO
    clock: Clock = field(default_factory=SystemClock)
    context: dict[str, str] = field(default_factory=dict)
    _lines: list[str] = field(default_factory=list, init=False)

    def bind(self, **context: str) -> EventLog:
        """Return a child log that stamps extra fields onto every event."""
        merged = dict(self.context)
        merged.update(context)
        child = EventLog(sink=self.sink, minimum=self.minimum, clock=self.clock, context=merged)
        child._lines = self._lines
        return child

    def emit(self, level: Level, event: str, /, **fields: Any) -> None:
        if level < self.minimum:
            return
        clash = RESERVED_KEYS & set(fields)
        if clash:
            raise StanchionError(f"event field shadows the envelope: {sorted(clash)[0]}")
        payload: dict[str, Any] = {
            "at": self.clock.now().isoformat(),
            "level": level.name,
            "event": event,
        }
        payload.update(self.context)
        payload.update(fields)
        line = json.dumps(payload, sort_keys=False, separators=(",", ":"), default=str)
        self._lines.append(line)
        if self.sink is not None:
            self.sink.write(line + "\n")

    def debug(self, event: str, /, **fields: Any) -> None:
        self.emit(Level.DEBUG, event, **fields)

    def info(self, event: str, /, **fields: Any) -> None:
        self.emit(Level.INFO, event, **fields)

    def warn(self, event: str, /, **fields: Any) -> None:
        self.emit(Level.WARN, event, **fields)

    def error(self, event: str, /, **fields: Any) -> None:
        self.emit(Level.ERROR, event, **fields)

    def exception(self, event: str, exc: BaseException, /, **fields: Any) -> None:
        """Log a failure with the stable error code where there is one."""
        if isinstance(exc, StanchionError):
            self.error(event, code=exc.code, message=exc.message, **fields)
        else:
            self.error(event, code="python." + type(exc).__name__, message=str(exc), **fields)

    @property
    def lines(self) -> list[str]:
        """Everything written, kept so a test does not need a file."""
        return list(self._lines)

    def records(self) -> list[dict[str, Any]]:
        return [json.loads(line) for line in self._lines]


def null_log() -> EventLog:
    """A log that keeps nothing, for code paths that must not be chatty."""
    return EventLog(sink=None, minimum=Level.ERROR)


def counting_sink(target: Callable[[str], None]) -> TextIO:
    """Adapt a plain callable into the file-like object EventLog wants."""

    class _Sink:
        def write(self, text: str) -> int:
            target(text)
            return len(text)

        def flush(self) -> None:
            return None

    return _Sink()  # type: ignore[return-value]
