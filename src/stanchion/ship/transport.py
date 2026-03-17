"""Where a batch actually goes.

Four transports, and three of them are not a fallback for the fourth:

* **http**, the ordinary path to the estate collector
* **file**, which writes batches into a directory. A site whose modem has been
  dead for a week gets visited, and the engineer copies that directory onto a
  stick. It is the only way some of the estate's rural sites have ever been
  read.
* **memory**, for tests and for the replay tool
* **null**, which discards. Used when measuring how fast the rest of the
  pipeline is without the link in the way.

The HTTP one is written on ``urllib`` rather than a client library. It sends one
small JSON POST and reads a status code, the site image is already tight, and a
dependency whose next version changes its timeout semantics is a dependency that
can silently stop a forecourt from reporting.

Status codes are mapped once, here, and the mapping is the interesting part. A
408 or a 5xx is retryable. A 429 is throttled and its ``Retry-After`` is obeyed.
A 4xx that is not one of those is terminal, because offering the same body again
will get the same refusal and each attempt costs metered bytes.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from stanchion.errors import ShipError
from stanchion.ship.backoff import Outcome
from stanchion.ship.batch import Batch
from stanchion.ship.signing import Signer
from stanchion.version import user_agent

DEFAULT_TIMEOUT = 30.0

SIGNATURE_HEADER = "X-Stanchion-Signature"
SIGNATURE_SUFFIX = ".sig"

RETRYABLE_STATUS = frozenset({408, 425, 500, 502, 503, 504})
THROTTLED_STATUS = 429


@dataclass(frozen=True, slots=True)
class Response:
    """What one send produced."""

    outcome: Outcome
    status: int = 0
    retry_after: float | None = None
    message: str = ""
    bytes_sent: int = 0

    @property
    def ok(self) -> bool:
        return self.outcome is Outcome.OK


def outcome_for(status: int) -> Outcome:
    """Map an HTTP status onto what to do about it."""
    if 200 <= status < 300:
        return Outcome.OK
    if status == THROTTLED_STATUS:
        return Outcome.THROTTLED
    if status in RETRYABLE_STATUS or status >= 500:
        return Outcome.RETRYABLE
    return Outcome.TERMINAL


def parse_retry_after(raw: str | None) -> float | None:
    """Read a Retry-After header, seconds form only."""
    if raw is None:
        return None
    try:
        seconds = float(raw.strip())
    except ValueError:
        return None
    return seconds if seconds >= 0 else None


class Transport(Protocol):
    """Somewhere a batch can be sent."""

    def send(self, batch: Batch) -> Response: ...


@dataclass(slots=True)
class MemoryTransport:
    """Keeps what it was given and answers from a script."""

    script: list[Response] = field(default_factory=list)
    received: list[Batch] = field(default_factory=list)
    default: Response = field(default_factory=lambda: Response(outcome=Outcome.OK, status=200))

    def send(self, batch: Batch) -> Response:
        self.received.append(batch)
        answer = self.script.pop(0) if self.script else self.default
        return Response(
            outcome=answer.outcome,
            status=answer.status,
            retry_after=answer.retry_after,
            message=answer.message,
            bytes_sent=batch.wire_bytes,
        )

    @property
    def batch_ids(self) -> list[str]:
        return [batch.batch_id for batch in self.received]


@dataclass(slots=True)
class NullTransport:
    """Accepts everything and keeps nothing."""

    sent: int = 0
    bytes_sent: int = 0

    def send(self, batch: Batch) -> Response:
        size = batch.wire_bytes
        self.sent += 1
        self.bytes_sent += size
        return Response(outcome=Outcome.OK, status=200, bytes_sent=size)


@dataclass(slots=True)
class FileTransport:
    """Writes each batch into a directory, named by its batch id.

    The signature goes beside it in its own file rather than inside the body.
    Detached is what makes it useful here: the batch is the same bytes whether
    it went over the modem or onto a stick, so the collector checks one thing
    either way, and a directory that lost its ``.sig`` files is obviously
    unsigned rather than quietly unverifiable.
    """

    directory: Path
    signer: Signer | None = None
    written: int = 0

    def send(self, batch: Batch) -> Response:
        body = batch.encode()
        try:
            self.directory.mkdir(parents=True, exist_ok=True)
            target = self.directory / f"{batch.batch_id}.json"
            target.write_bytes(body)
            if self.signer is not None:
                beside = target.with_name(target.name + SIGNATURE_SUFFIX)
                header = self.signer.header_for(batch, body)
                beside.write_text(header + "\n", encoding="utf-8")
        except OSError as exc:
            return Response(outcome=Outcome.RETRYABLE, message=str(exc))
        self.written += 1
        return Response(outcome=Outcome.OK, status=200, bytes_sent=len(body))


@dataclass(slots=True)
class HttpTransport:
    """One small JSON POST per batch."""

    endpoint: str
    timeout: float = DEFAULT_TIMEOUT
    token: str = ""
    signer: Signer | None = None
    opener: Any = None

    def __post_init__(self) -> None:
        if not self.endpoint.startswith(("http://", "https://")):
            raise ShipError("endpoint must be an http url", detail=self.endpoint)
        if self.timeout <= 0:
            raise ShipError("timeout must be positive", detail=str(self.timeout))

    def _request(self, batch: Batch, body: bytes) -> urllib.request.Request:
        headers = {
            "Content-Type": "application/json",
            "User-Agent": user_agent(),
            "Content-Length": str(len(body)),
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if self.signer is not None:
            headers[SIGNATURE_HEADER] = self.signer.header_for(batch, body)
        return urllib.request.Request(self.endpoint, data=body, headers=headers, method="POST")

    def send(self, batch: Batch) -> Response:
        body = batch.encode()
        request = self._request(batch, body)
        opener = self.opener if self.opener is not None else urllib.request.urlopen
        try:
            with opener(request, timeout=self.timeout) as answer:
                status = int(getattr(answer, "status", 200))
                return Response(outcome=outcome_for(status), status=status, bytes_sent=len(body))
        except urllib.error.HTTPError as exc:
            return Response(
                outcome=outcome_for(exc.code),
                status=exc.code,
                retry_after=parse_retry_after(exc.headers.get("Retry-After")),
                message=exc.reason if isinstance(exc.reason, str) else str(exc.reason),
                bytes_sent=len(body),
            )
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            # No status at all: the link went away mid request. Always worth
            # another go, and it cost us the bytes either way.
            return Response(outcome=Outcome.RETRYABLE, message=str(exc), bytes_sent=len(body))


def decode_body(raw: bytes) -> dict[str, Any]:
    """Read a collector reply, tolerating one that is not JSON at all."""
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return {}
    return payload if isinstance(payload, dict) else {}
