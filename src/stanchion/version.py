"""Version and build identity.

Site controllers are flashed from an image and then live for years without
anyone touching them, so the version string has to survive being read out of a
log line six months later. Keep it plain.
"""

from __future__ import annotations

VERSION = "0.1.0"

#: Bumped whenever the on disk spool layout changes in a way an older reader
#: cannot understand. Independent of VERSION on purpose.
SPOOL_FORMAT = 1


def version_string() -> str:
    return f"stanchion {VERSION}"


def user_agent() -> str:
    """Identify this build to the collector."""
    return f"stanchion/{VERSION} spool/{SPOOL_FORMAT}"
