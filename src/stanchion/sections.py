"""The checks every config section was doing its own copy of.

There were seven of them and they had drifted. Some said "unknown spool key",
one said "unknown config section", one sorted the offending names and one did
not, and the multiline rules had a version that named the rule instead of the
section. None of that is wrong exactly, but a commissioning engineer reading an
error at eleven at night should not have to work out which of two phrasings
they are looking at.

There is one thing here the copies did not do, and it is the reason this is
worth a module rather than a comment. A rejected key is nearly always a typo of
a real one, and the parser knows the real ones, so it can say which. Without
that the message names the key that is wrong and leaves the engineer to find
the spelling that is right, in a document they do not have on the laptop.

Unknown keys are refused rather than ignored. A config file is the only way to
change what an unattended box does, so a key that is quietly skipped is a
setting somebody believes is in force and is not, and it is discovered when the
thing it was supposed to prevent happens.
"""

from __future__ import annotations

from difflib import get_close_matches
from typing import Any

from stanchion.errors import ConfigError

#: How alike a key has to be to a real one before it is worth suggesting.
SUGGESTION_CUTOFF = 0.6


def suggest(name: str, known: set[str]) -> str:
    """The real key a rejected one was probably meant to be, if there is one."""
    close = get_close_matches(name, sorted(known), n=1, cutoff=SUGGESTION_CUTOFF)
    return close[0] if close else ""


def check_keys(raw: dict[str, Any], known: set[str], section: str) -> dict[str, Any]:
    """Refuse a section carrying any key this build does not know.

    Returns the mapping so a caller can use it as an expression, which is what
    lets the check sit at the top of a builder without a spare statement.
    """
    unknown = set(raw) - known
    if not unknown:
        return raw
    named = sorted(unknown)
    hint = suggest(named[0], known) if len(named) == 1 else ""
    detail = ", ".join(named)
    if hint:
        detail = f"{detail}, did you mean {hint}"
    raise ConfigError(f"unknown {section} key", detail=detail)


def section(raw: dict[str, Any], name: str, owner: str) -> dict[str, Any]:
    """One nested block, checked to be a block and defaulted to an empty one."""
    found = raw.get(name, {})
    if found is None:
        # An empty block in YAML parses as None rather than as an empty
        # mapping, and writing the key with nothing under it is how somebody
        # says "defaults please".
        return {}
    if not isinstance(found, dict):
        raise ConfigError(f"the {name} section must be a mapping", detail=owner)
    return found


def require(raw: dict[str, Any], name: str, section_name: str, noun: str = "") -> Any:
    """A key that has to be there.

    ``noun`` is there so a section can keep the words it already used. The
    message an engineer has seen before is worth more than a tidy one, and
    "config has no site id" reads better than the key name would.
    """
    if name not in raw:
        raise ConfigError(f"{section_name} has no {noun or name}")
    return raw[name]
