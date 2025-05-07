"""Stitching an application's own multi line output back together.

This is a different problem from chunk joining and it is worth being clear about
why. A chunk is a runtime artefact: the application wrote one line and the
runtime cut it up, so putting it back is lossless and unconditional. A stack
trace is the opposite: the application really did write eight lines, and whether
they are one event or eight is a judgement about that application.

So the rules live in config, per unit, and a rule is stated in exactly one of two
ways:

* a **start** pattern, which says a line matching it begins a new event. The
  payment kernel prefixes every event with a sequence number, so anything without
  one belongs to the line above.
* a **continuation** pattern, which says a line matching it belongs to the event
  above. The tank gauge driver is a JVM and its traces indent every frame.

Both at once is refused. They are two framings of the same decision and a config
that gives both is a config where somebody meant one of them and the reviewer
could not tell which.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field, replace

from stanchion.errors import ConfigError
from stanchion.records import LogRecord, Stream

DEFAULT_MAX_LINES = 200
JOIN_WITH = "\n"
STITCHED_LABEL = "stitched"


@dataclass(frozen=True, slots=True)
class MultilineRule:
    """How one unit's output is framed into events."""

    unit: str
    start: str | None = None
    continuation: str | None = None
    max_lines: int = DEFAULT_MAX_LINES
    negate: bool = False

    def __post_init__(self) -> None:
        if (self.start is None) == (self.continuation is None):
            raise ConfigError(
                "a multiline rule needs exactly one of start or continuation",
                detail=self.unit,
            )
        if self.max_lines < 2:
            raise ConfigError("max_lines must be at least 2", detail=str(self.max_lines))
        for pattern in (self.start, self.continuation):
            if pattern is None:
                continue
            try:
                re.compile(pattern)
            except re.error as exc:
                raise ConfigError("multiline pattern does not compile", detail=str(exc)) from exc

    def compiled(self) -> re.Pattern[str]:
        pattern = self.start if self.start is not None else self.continuation
        assert pattern is not None
        return re.compile(pattern)

    def continues(self, message: str) -> bool:
        """True when this line belongs to the event above it."""
        matched = self.compiled().search(message) is not None
        if self.negate:
            matched = not matched
        return matched if self.continuation is not None else not matched


@dataclass(slots=True)
class _Held:
    head: LogRecord
    lines: list[str]
    sources: list[object]
    truncated: bool = False


@dataclass(slots=True)
class Stitcher:
    """Applies one rule to the records of one unit."""

    rule: MultilineRule
    _held: dict[Stream, _Held] = field(default_factory=dict, init=False)

    def feed(self, record: LogRecord) -> list[LogRecord]:
        """Take one record. Returns whatever became complete because of it."""
        held = self._held.get(record.stream)
        if held is None:
            self._held[record.stream] = _Held(
                head=record, lines=[record.message], sources=list(record.sources)
            )
            return []
        if not self.rule.continues(record.message):
            out = [_assemble(held)]
            self._held[record.stream] = _Held(
                head=record, lines=[record.message], sources=list(record.sources)
            )
            return out
        if len(held.lines) >= self.rule.max_lines:
            held.truncated = True
            return []
        held.lines.append(record.message)
        held.sources.extend(record.sources)
        return []

    def flush(self) -> list[LogRecord]:
        """Emit everything still held, oldest stream first."""
        out = [_assemble(held) for held in self._held.values()]
        self._held.clear()
        return out

    def held_streams(self) -> tuple[Stream, ...]:
        return tuple(self._held)


def _assemble(held: _Held) -> LogRecord:
    if len(held.lines) == 1 and not held.truncated:
        return held.head
    labels = dict(held.head.labels)
    labels[STITCHED_LABEL] = str(len(held.lines))
    if held.truncated:
        labels["truncated"] = "true"
    return replace(
        held.head,
        message=JOIN_WITH.join(held.lines),
        sources=tuple(held.sources),  # type: ignore[arg-type]
        labels=labels,
    )


def rules_from_mapping(raw: dict[str, object]) -> dict[str, MultilineRule]:
    """Build the per unit rule table from the config section."""
    out: dict[str, MultilineRule] = {}
    for unit, body in raw.items():
        if not isinstance(body, dict):
            raise ConfigError("a multiline rule must be a mapping", detail=unit)
        unknown = set(body) - {"start", "continuation", "max_lines", "negate"}
        if unknown:
            raise ConfigError("unknown multiline key", detail=", ".join(sorted(unknown)))
        start = body.get("start")
        continuation = body.get("continuation")
        out[unit] = MultilineRule(
            unit=unit,
            start=None if start is None else str(start),
            continuation=None if continuation is None else str(continuation),
            max_lines=int(body.get("max_lines", DEFAULT_MAX_LINES)),
            negate=bool(body.get("negate", False)),
        )
    return out
