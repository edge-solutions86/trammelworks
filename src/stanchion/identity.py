"""Site, unit and container identity, plus the dedupe key.

Shipping is at least once, so the collector will see the same record twice
whenever an acknowledgement is lost on a flaky LTE link. Dedupe therefore has to
happen on a key that both ends can compute from the record alone, which rules
out anything drawn from the spool position or the upload attempt.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from hashlib import blake2b

from stanchion.errors import ConfigError
from stanchion.records import LogRecord

#: Forecourt sites are numbered by the estate, not by us: two letter country,
#: two to three letter region, four digit site. Anything else is a typo in the
#: commissioning file and a typo there means logs land under the wrong estate.
SITE_PATTERN = re.compile(r"^[A-Z]{2}-[A-Z]{2,3}-\d{4}$")

#: Container runtimes hand out 64 hex characters. Everything human facing uses
#: the first twelve, which is what the runtime prints too.
CONTAINER_ID_PATTERN = re.compile(r"^[0-9a-f]{64}$")

SHORT_ID_LENGTH = 12

UNIT_PATTERN = re.compile(r"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")

KEY_BYTES = 16


def parse_site(raw: str) -> str:
    """Validate and normalize a site identifier."""
    cleaned = raw.strip().upper()
    if not SITE_PATTERN.match(cleaned):
        raise ConfigError("malformed site id", detail=raw)
    return cleaned


def parse_unit(raw: str) -> str:
    """Validate a unit name, which is the container's service name."""
    cleaned = raw.strip().lower()
    if not UNIT_PATTERN.match(cleaned):
        raise ConfigError("malformed unit name", detail=raw)
    return cleaned


def short_id(container_id: str) -> str:
    """Shorten a full container id the way the runtime does."""
    cleaned = container_id.strip().lower()
    if not CONTAINER_ID_PATTERN.match(cleaned):
        raise ConfigError("malformed container id", detail=container_id)
    return cleaned[:SHORT_ID_LENGTH]


@dataclass(frozen=True, slots=True)
class UnitRef:
    """A running container, identified the way a site controller sees one.

    ``attempt`` is the restart counter. The pump bridge restarts on every
    dispenser reset, so the same unit name covers many containers in a day and
    the attempt is what separates their logs.
    """

    site: str
    unit: str
    container_id: str
    attempt: int = 0

    def __post_init__(self) -> None:
        object.__setattr__(self, "site", parse_site(self.site))
        object.__setattr__(self, "unit", parse_unit(self.unit))
        object.__setattr__(self, "container_id", short_id(self.container_id))
        if self.attempt < 0:
            raise ConfigError("negative restart attempt", detail=str(self.attempt))

    @property
    def slug(self) -> str:
        """The stable path safe name used for spool segments and checkpoints."""
        return f"{self.site}_{self.unit}_{self.attempt}"

    def __str__(self) -> str:
        return f"{self.site}/{self.unit}#{self.attempt}({self.container_id})"


def record_key(record: LogRecord) -> str:
    """Compute the dedupe key for a record.

    Deliberately built only from fields the collector also receives. The
    timestamp is taken to microsecond precision in UTC so that two site
    controllers in different offsets agree, and the message is hashed in UTF-8
    so that an encoding round trip does not change the answer.
    """
    digest = blake2b(digest_size=KEY_BYTES)
    digest.update(record.site.encode("utf-8"))
    digest.update(b"\x1f")
    digest.update(record.unit.encode("utf-8"))
    digest.update(b"\x1f")
    digest.update(record.stream.value.encode("ascii"))
    digest.update(b"\x1f")
    digest.update(record.utc.isoformat().encode("ascii"))
    digest.update(b"\x1f")
    digest.update(record.message.encode("utf-8"))
    return digest.hexdigest()
