"""Proving a batch came off the box it says it did.

The uplink is an LTE modem in a cabinet behind a forecourt, and the batches on
it carry payment adjacent records. TLS covers the wire, but it does not cover
the other route these take: a site whose modem has been dead for a week is
visited and its batches leave on a memory stick. Nothing about a file on a stick
says which site wrote it, and by the time it reaches the estate collector the
only person who could say has gone home.

So each batch carries a detached signature. It is HMAC over SHA-256 with a key
the site is commissioned with, and what gets signed is not the body on its own::

    stanchion-batch-v1 \\n key id \\n timestamp \\n site \\n batch id \\n body

Binding the key id, the site and the batch id into the signed string is what
stops a batch being replayed as though it came from a different site or under a
different key. Signing the body alone would let all three be rewritten in the
envelope while the signature still checked out.

The timestamp is signed and it is checked against a window, so a batch pulled
off a stick a month later is refused rather than accepted as fresh. That window
is generous by design: the clocks on these boxes drift, which is a whole module
of this system, and a signature check is the wrong place to relitigate it.

The key never goes in the config file. The config names a path, the file is read
at startup, and its contents are held in memory only. A commissioning engineer
copies site configs between boxes routinely and a secret in one of them would
end up on every forecourt in the estate.
"""

from __future__ import annotations

import hmac
import re
from dataclasses import dataclass, field
from hashlib import sha256
from pathlib import Path

from stanchion.clocks import Clock, SystemClock
from stanchion.errors import ParseError, ShipError
from stanchion.ship.batch import Batch
from stanchion.timestamps import NanoTime

SIGNATURE_VERSION = "stanchion-batch-v1"
SEPARATOR = b"\n"

#: Anything shorter is not a key, whatever the commissioning sheet says.
MIN_SECRET_BYTES = 32

MAX_SECRET_BYTES = 4096

KEY_ID_PATTERN = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")

#: How far out of step a batch's timestamp may be and still verify. Ten days,
#: because that is roughly how long a rural site waits for a visit.
DEFAULT_SKEW_SECONDS = 10 * 24 * 60 * 60

HEADER_PATTERN = re.compile(
    r"^(?P<version>\S+) keyid=(?P<keyid>\S+) ts=(?P<ts>\S+) sig=(?P<sig>[0-9a-f]{64})$"
)


@dataclass(frozen=True, slots=True)
class SigningKey:
    """The secret one site signs with, and the name the collector knows it by."""

    key_id: str
    secret: bytes

    def __post_init__(self) -> None:
        if not KEY_ID_PATTERN.match(self.key_id):
            raise ShipError("malformed signing key id", detail=self.key_id)
        if len(self.secret) < MIN_SECRET_BYTES:
            raise ShipError(
                "signing secret is too short",
                detail=f"{len(self.secret)} bytes, {MIN_SECRET_BYTES} needed",
            )
        if len(self.secret) > MAX_SECRET_BYTES:
            raise ShipError("signing secret is implausibly long", detail=str(len(self.secret)))

    def __repr__(self) -> str:
        """Never the secret. These objects end up in tracebacks."""
        return f"SigningKey(key_id={self.key_id!r}, secret=<{len(self.secret)} bytes>)"


def read_key(path: Path, key_id: str) -> SigningKey:
    """Read a signing secret off disk.

    The file holds the secret and nothing else. Surrounding whitespace is
    stripped because an editor will add a trailing newline and a key that
    stops working when somebody opens the file is not worth the strictness.
    """
    try:
        raw = path.read_bytes()
    except OSError as exc:
        raise ShipError("signing key file unreadable", detail=str(exc)) from exc
    secret = raw.strip()
    if not secret:
        raise ShipError("signing key file is empty", detail=str(path))
    return SigningKey(key_id=key_id, secret=secret)


def signing_string(key_id: str, timestamp: str, site: str, batch_id: str, body: bytes) -> bytes:
    """Everything the signature covers, in order."""
    parts = [SIGNATURE_VERSION, key_id, timestamp, site, batch_id]
    for part in parts:
        if SEPARATOR.decode() in part:
            raise ShipError("a signed field cannot contain a newline", detail=part)
    return SEPARATOR.join(item.encode("utf-8") for item in parts) + SEPARATOR + body


@dataclass(frozen=True, slots=True)
class Signature:
    """A detached signature and the envelope it needs to be checked against."""

    key_id: str
    timestamp: str
    digest: str
    version: str = SIGNATURE_VERSION

    def header(self) -> str:
        """The one line form that goes in a header or beside a file."""
        return f"{self.version} keyid={self.key_id} ts={self.timestamp} sig={self.digest}"


def parse_signature(raw: str) -> Signature:
    """Read the one line form back."""
    match = HEADER_PATTERN.match(raw.strip())
    if match is None:
        raise ShipError("signature is malformed", detail=raw[:80])
    return Signature(
        key_id=match.group("keyid"),
        timestamp=match.group("ts"),
        digest=match.group("sig"),
        version=match.group("version"),
    )


def sign(key: SigningKey, site: str, batch_id: str, body: bytes, at: NanoTime) -> Signature:
    """Sign one batch."""
    timestamp = at.isoformat()
    material = signing_string(key.key_id, timestamp, site, batch_id, body)
    digest = hmac.new(key.secret, material, sha256).hexdigest()
    return Signature(key_id=key.key_id, timestamp=timestamp, digest=digest)


@dataclass(frozen=True, slots=True)
class Check:
    """Why a signature was accepted or refused."""

    ok: bool
    reason: str = ""

    def __bool__(self) -> bool:
        return self.ok


def verify(
    key: SigningKey,
    signature: Signature,
    site: str,
    batch_id: str,
    body: bytes,
    now: NanoTime,
    skew_seconds: float = DEFAULT_SKEW_SECONDS,
) -> Check:
    """Check a signature, and say plainly why not when it fails.

    Every failure returns rather than raises, because the caller is a collector
    walking a directory off a memory stick and one bad file must not stop it
    reading the rest.
    """
    if signature.version != SIGNATURE_VERSION:
        return Check(False, f"signed by another version: {signature.version}")
    if signature.key_id != key.key_id:
        return Check(False, f"signed by another key: {signature.key_id}")
    try:
        signed_at = NanoTime.parse(signature.timestamp)
    except ParseError:
        return Check(False, "signature timestamp is unreadable")
    drift = abs(now.epoch_ns - signed_at.epoch_ns) / 1_000_000_000
    if drift > skew_seconds:
        return Check(False, f"signed {drift:.0f} seconds away from now")
    expected = sign(key, site, batch_id, body, signed_at)
    if not hmac.compare_digest(expected.digest, signature.digest):
        return Check(False, "signature does not match")
    return Check(True)


@dataclass(slots=True)
class Signer:
    """Signs the batches one site sends."""

    key: SigningKey
    clock: Clock = field(default_factory=SystemClock)
    signed: int = 0

    def for_batch(self, batch: Batch, body: bytes) -> Signature:
        found = sign(self.key, batch.site, batch.batch_id, body, self.clock.now())
        self.signed += 1
        return found

    def header_for(self, batch: Batch, body: bytes) -> str:
        return self.for_batch(batch, body).header()
