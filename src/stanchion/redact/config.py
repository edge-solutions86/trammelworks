"""The ``redact`` section of a site config.

Kept in the redact package rather than in the config module so that the rule
vocabulary lives beside the code that enforces it. The config module knows this
section exists and hands the block over; it does not know what a keep length is.

One setting here can be turned off and it is worth saying why it exists. A test
site in a lab has no real card data and an engineer benchmarking the reader wants
the pass out of the way. ``enabled: false`` does that, and it is refused for any
site whose id is not in the lab range, because the one way this ends badly is a
production site commissioned from a lab template.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from stanchion.errors import ConfigError
from stanchion.redact.rules import Rule, rules_from_mapping
from stanchion.sections import check_keys, section

DEFAULT_KEEP_FIRST = 6
DEFAULT_KEEP_LAST = 4

#: Sites in this range are benches, not forecourts. Nothing in them takes money.
LAB_SITE_PREFIXES = ("XX-LAB-", "XX-DEV-")


@dataclass(frozen=True, slots=True)
class RedactConfig:
    """What the redaction pass does on this site."""

    enabled: bool = True
    keep_first: int = DEFAULT_KEEP_FIRST
    keep_last: int = DEFAULT_KEEP_LAST
    rules: tuple[Rule, ...] = ()

    def __post_init__(self) -> None:
        if self.keep_first < 0 or self.keep_last < 0:
            raise ConfigError("cannot keep a negative number of digits")
        if self.keep_first > 6:
            raise ConfigError(
                "keeping more than six leading digits is not allowed",
                detail=str(self.keep_first),
            )
        if self.keep_last > 4:
            raise ConfigError(
                "keeping more than four trailing digits is not allowed",
                detail=str(self.keep_last),
            )
        names = [item.name for item in self.rules]
        if len(set(names)) != len(names):
            raise ConfigError("two redaction rules share a name")

    def check_site(self, site: str) -> None:
        """Refuse a disabled pass anywhere that could see a real card."""
        if self.enabled:
            return
        if not site.startswith(LAB_SITE_PREFIXES):
            raise ConfigError("redaction can only be disabled on a lab site", detail=site)

    @property
    def rule_names(self) -> tuple[str, ...]:
        return tuple(item.name for item in self.rules)


@dataclass(slots=True)
class RedactSummary:
    """A one line description, for the doctor command."""

    enabled: bool
    keep_first: int
    keep_last: int
    rules: tuple[str, ...] = field(default_factory=tuple)

    def __str__(self) -> str:
        if not self.enabled:
            return "redaction DISABLED"
        shape = f"keep {self.keep_first}+{self.keep_last}"
        if not self.rules:
            return f"redaction on, {shape}, card rules only"
        return f"redaction on, {shape}, plus {len(self.rules)} site rules"


def summarize(config: RedactConfig) -> RedactSummary:
    return RedactSummary(
        enabled=config.enabled,
        keep_first=config.keep_first,
        keep_last=config.keep_last,
        rules=config.rule_names,
    )


def redact_from_mapping(raw: dict[str, Any]) -> RedactConfig:
    """Build the section, refusing anything it does not recognise."""
    check_keys(raw, {"enabled", "keep_first", "keep_last", "rules"}, "redact")
    rules_raw = section(raw, "rules", "redact")
    return RedactConfig(
        enabled=bool(raw.get("enabled", True)),
        keep_first=int(raw.get("keep_first", DEFAULT_KEEP_FIRST)),
        keep_last=int(raw.get("keep_last", DEFAULT_KEEP_LAST)),
        rules=tuple(rules_from_mapping(rules_raw)),
    )
