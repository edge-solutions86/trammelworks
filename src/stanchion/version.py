"""Version and build identity.

Site controllers are flashed from an image and then live for years without
anyone touching them, so the version string has to survive being read out of a
log line six months later. Keep it plain.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass

VERSION = "0.1.0"

#: Bumped whenever the on disk spool layout changes in a way an older reader
#: cannot understand. Independent of VERSION on purpose.
SPOOL_FORMAT = 1


#: The archive schema this build writes. Read by the doctor so an engineer can
#: tell a site that has not been restarted since an upgrade from one that has.
from stanchion.archive.schema import SCHEMA_VERSION  # noqa: E402


def version_string() -> str:
    return f"stanchion {VERSION}"


def user_agent() -> str:
    """Identify this build to the collector."""
    return f"stanchion/{VERSION} spool/{SPOOL_FORMAT}"


@dataclass(frozen=True, slots=True)
class Build:
    """Everything a support call needs to identify what is running.

    Four numbers rather than one, because they move independently and the
    interesting failures are the ones where they disagree. A site running this
    version against a spool written by an older format is a site whose segments
    will not read, and the version string alone cannot say so.
    """

    version: str = VERSION
    spool_format: int = SPOOL_FORMAT
    archive_schema: int = SCHEMA_VERSION
    python: str = ""
    platform: str = ""

    @classmethod
    def current(cls) -> Build:
        return cls(
            python=f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}",
            platform=sys.platform,
        )

    def as_dict(self) -> dict[str, object]:
        return {
            "version": self.version,
            "spool_format": self.spool_format,
            "archive_schema": self.archive_schema,
            "python": self.python,
            "platform": self.platform,
        }

    def lines(self) -> list[str]:
        """One fact per line, the way somebody pastes it into a ticket."""
        return [
            f"stanchion    {self.version}",
            f"spool format {self.spool_format}",
            f"archive      schema {self.archive_schema}",
            f"python       {self.python}",
            f"platform     {self.platform}",
        ]

    def __str__(self) -> str:
        return "\n".join(self.lines())
