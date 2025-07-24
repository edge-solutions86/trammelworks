"""Site written redaction rules.

The card rules are fixed because the card schemes are. Everything else varies by
estate: one brand's loyalty numbers are eleven digits, another's are a letter and
nine, and the fleet operators put a driver identifier on the pump keypad that is
a person's payroll number.

A rule is a name, a pattern, and what to do with what it matched. The name is
not decoration: the audit trail counts by name, so a rule that fires ten thousand
times a day is visible as itself rather than as an anonymous line in a total.

Two actions, and the difference matters:

* **drop** replaces the match with a fixed marker. Nothing survives.
* **mask** keeps the leading and trailing characters the site asked for. Only
  ever right where the remaining characters cannot identify anyone on their own.

A rule may name a capture group to act on. Without one the whole match is acted
on, which is nearly always too much: ``driver=(\\d+)`` should redact the number
and keep the word ``driver``, otherwise the line stops being readable and the
support engineer cannot see which field went.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import StrEnum
from typing import Any

from stanchion.errors import ConfigError

DEFAULT_MARKER = "[redacted]"
MASK_CHARACTER = "*"
GROUP_NAME = "secret"


class Action(StrEnum):
    """What happens to what a rule matched."""

    DROP = "drop"
    MASK = "mask"


@dataclass(frozen=True, slots=True)
class Rule:
    """One site written rule."""

    name: str
    pattern: str
    action: Action = Action.DROP
    keep_first: int = 0
    keep_last: int = 0
    marker: str = DEFAULT_MARKER

    def __post_init__(self) -> None:
        if not self.name or not self.name.replace("_", "").replace("-", "").isalnum():
            raise ConfigError("malformed rule name", detail=self.name)
        try:
            compiled = re.compile(self.pattern)
        except re.error as exc:
            raise ConfigError("rule pattern does not compile", detail=str(exc)) from exc
        if compiled.groups > 1 and GROUP_NAME not in compiled.groupindex:
            raise ConfigError("a rule with several groups must name one 'secret'", detail=self.name)
        if self.keep_first < 0 or self.keep_last < 0:
            raise ConfigError("cannot keep a negative number of characters", detail=self.name)
        if self.action is Action.DROP and (self.keep_first or self.keep_last):
            raise ConfigError("a drop rule cannot keep characters", detail=self.name)

    def compiled(self) -> re.Pattern[str]:
        return re.compile(self.pattern)

    def target_span(self, match: re.Match[str]) -> tuple[int, int]:
        """Which part of the match this rule acts on."""
        if GROUP_NAME in match.re.groupindex:
            return match.span(GROUP_NAME)
        if match.re.groups == 1:
            return match.span(1)
        return match.span(0)

    def replacement_for(self, value: str) -> str:
        if self.action is Action.DROP:
            return self.marker
        hidden = len(value) - self.keep_first - self.keep_last
        if hidden <= 0:
            return MASK_CHARACTER * len(value)
        head = value[: self.keep_first]
        tail = value[len(value) - self.keep_last :] if self.keep_last else ""
        return head + MASK_CHARACTER * hidden + tail


@dataclass(frozen=True, slots=True)
class RuleHit:
    """Where a rule fired."""

    rule: str
    start: int
    end: int
    text: str


def apply_rule(rule: Rule, text: str) -> tuple[str, list[RuleHit]]:
    """Run one rule over a line, returning the new line and what it caught."""
    hits: list[RuleHit] = []
    out: list[str] = []
    cursor = 0
    for match in rule.compiled().finditer(text):
        start, end = rule.target_span(match)
        if start < 0 or start < cursor:
            continue
        value = text[start:end]
        hits.append(RuleHit(rule=rule.name, start=start, end=end, text=value))
        out.append(text[cursor:start])
        out.append(rule.replacement_for(value))
        cursor = end
    out.append(text[cursor:])
    return "".join(out), hits


def rule_from_mapping(name: str, raw: dict[str, Any]) -> Rule:
    """Build one rule from its config block."""
    unknown = set(raw) - {"pattern", "action", "keep_first", "keep_last", "marker"}
    if unknown:
        raise ConfigError("unknown rule key", detail=", ".join(sorted(unknown)))
    if "pattern" not in raw:
        raise ConfigError("rule has no pattern", detail=name)
    action_raw = str(raw.get("action", Action.DROP.value)).strip().lower()
    try:
        action = Action(action_raw)
    except ValueError as exc:
        raise ConfigError("unknown rule action", detail=action_raw) from exc
    return Rule(
        name=name,
        pattern=str(raw["pattern"]),
        action=action,
        keep_first=int(raw.get("keep_first", 0)),
        keep_last=int(raw.get("keep_last", 0)),
        marker=str(raw.get("marker", DEFAULT_MARKER)),
    )


def rules_from_mapping(raw: dict[str, Any]) -> list[Rule]:
    """Build the whole rule list, in the order the config wrote them."""
    out: list[Rule] = []
    for name, body in raw.items():
        if not isinstance(body, dict):
            raise ConfigError("a rule must be a mapping", detail=str(name))
        out.append(rule_from_mapping(str(name), body))
    return out
