"""Putting split lines back together.

The runtime cuts an application line into 16 KiB chunks and marks every piece
but the last as partial. Reassembly is the whole job of this module and there
are four rules in it that are easy to get wrong:

* **stdout and stderr interleave.** The payment kernel writes a long trace to
  stderr while the heartbeat carries on writing to stdout, so the two streams
  need separate pending buffers. Joining across them produces a line that never
  existed, which is worse than losing one.
* **the first chunk owns the timestamp.** The runtime stamps every chunk with
  the moment it wrote that chunk. Taking the last one dates a 400 KiB trace at
  the moment it finished rather than the moment the fault happened, and the
  fault is what someone is looking for.
* **a line can be unbounded.** A dispenser stuck in a reset loop has printed a
  single 90 MB line. There is a cap, the line is cut at it, and what is emitted
  says so rather than pretending it was whole.
* **end of file is not end of line.** The last chunk of a live file has no
  successor yet. Flushing at that point invents a truncated record; waiting is
  correct. Flushing is only right when the file is finished, which the tailer
  knows and the joiner does not, so it is a separate call.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from stanchion.records import Fragment, LogRecord, SourceRef, Stream

DEFAULT_MAX_LINE_BYTES = 1024 * 1024
DEFAULT_MAX_CHUNKS = 4096

TRUNCATED_LABEL = "truncated"
LOSSY_LABEL = "lossy"


@dataclass(frozen=True, slots=True)
class JoinLimits:
    """What a single reassembled line is allowed to cost."""

    max_line_bytes: int = DEFAULT_MAX_LINE_BYTES
    max_chunks: int = DEFAULT_MAX_CHUNKS

    def __post_init__(self) -> None:
        if self.max_line_bytes <= 0:
            raise ValueError("max_line_bytes must be positive")
        if self.max_chunks <= 0:
            raise ValueError("max_chunks must be positive")


@dataclass(slots=True)
class _Pending:
    """Chunks seen so far for one stream."""

    first: Fragment
    parts: list[str] = field(default_factory=list)
    sources: list[SourceRef] = field(default_factory=list)
    size: int = 0
    lossy: bool = False
    truncated: bool = False
    dropping: bool = False


@dataclass(slots=True)
class JoinStats:
    """Numbers the nightly summary reports and support asks about."""

    fragments: int = 0
    records: int = 0
    joined: int = 0
    truncated: int = 0
    dropped_chunks: int = 0
    flushed_open: int = 0


@dataclass(slots=True)
class Joiner:
    """Reassembles fragments from one container into records."""

    site: str
    unit: str
    limits: JoinLimits = field(default_factory=JoinLimits)
    stats: JoinStats = field(default_factory=JoinStats)
    _pending: dict[Stream, _Pending] = field(default_factory=dict, init=False)

    def feed(self, fragment: Fragment) -> LogRecord | None:
        """Take one physical line. Returns a record when one is complete."""
        self.stats.fragments += 1
        pending = self._pending.get(fragment.stream)
        if pending is None:
            if fragment.complete:
                self.stats.records += 1
                return self._single(fragment)
            self._pending[fragment.stream] = self._open(fragment)
            return None
        self._append(pending, fragment)
        if fragment.partial:
            return None
        del self._pending[fragment.stream]
        self.stats.records += 1
        self.stats.joined += 1
        if pending.truncated:
            self.stats.truncated += 1
        return self._assemble(pending)

    def flush(self, stream: Stream | None = None) -> list[LogRecord]:
        """Emit whatever is still pending, because the file has finished.

        Only correct once nothing more can arrive. A record produced this way is
        marked truncated: the application meant to write more and never got to.
        """
        targets = [stream] if stream is not None else list(self._pending)
        out: list[LogRecord] = []
        for target in targets:
            pending = self._pending.pop(target, None)
            if pending is None:
                continue
            pending.truncated = True
            self.stats.records += 1
            self.stats.joined += 1
            self.stats.truncated += 1
            self.stats.flushed_open += 1
            out.append(self._assemble(pending))
        return out

    def pending_streams(self) -> tuple[Stream, ...]:
        return tuple(self._pending)

    def _open(self, fragment: Fragment) -> _Pending:
        pending = _Pending(first=fragment)
        self._append(pending, fragment)
        return pending

    def _append(self, pending: _Pending, fragment: Fragment) -> None:
        pending.lossy = pending.lossy or fragment.lossy
        if pending.dropping:
            self.stats.dropped_chunks += 1
            return
        if len(pending.parts) >= self.limits.max_chunks:
            pending.truncated = True
            pending.dropping = True
            self.stats.dropped_chunks += 1
            return
        room = self.limits.max_line_bytes - pending.size
        chunk = fragment.message
        size = len(chunk.encode("utf-8"))
        if size > room:
            # The byte cap can land inside this chunk, so keep the part that
            # fits. The chunk cap above cannot, so it keeps nothing.
            pending.truncated = True
            pending.dropping = True
            kept = _cut_to_bytes(chunk, room) if room > 0 else ""
            if kept:
                pending.parts.append(kept)
                pending.sources.append(fragment.source)
                pending.size += len(kept.encode("utf-8"))
            self.stats.dropped_chunks += 1
            return
        pending.parts.append(chunk)
        pending.sources.append(fragment.source)
        pending.size += size

    def _single(self, fragment: Fragment) -> LogRecord:
        labels: dict[str, str] = {}
        if fragment.lossy:
            labels[LOSSY_LABEL] = "true"
        return LogRecord(
            site=self.site,
            unit=self.unit,
            stream=fragment.stream,
            observed_at=fragment.observed_at,
            message=fragment.message,
            sources=(fragment.source,),
            labels=labels,
            lossy=fragment.lossy,
        )

    def _assemble(self, pending: _Pending) -> LogRecord:
        labels: dict[str, str] = {}
        if pending.truncated:
            labels[TRUNCATED_LABEL] = "true"
        if pending.lossy:
            labels[LOSSY_LABEL] = "true"
        return LogRecord(
            site=self.site,
            unit=self.unit,
            stream=pending.first.stream,
            observed_at=pending.first.observed_at,
            message="".join(pending.parts),
            sources=tuple(pending.sources),
            labels=labels,
            lossy=pending.lossy,
        )


def _cut_to_bytes(text: str, limit: int) -> str:
    """Cut a string to at most ``limit`` UTF-8 bytes without splitting a rune."""
    encoded = text.encode("utf-8")
    if len(encoded) <= limit:
        return text
    return encoded[:limit].decode("utf-8", errors="ignore")
