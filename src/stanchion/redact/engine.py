"""The redaction pass, and the order it runs in.

The order is not a preference. Stripes go first because a track 2 image
*contains* a PAN, and masking the PAN inside it would leave the expiry and the
service code sitting there next to a masked number, which reads as safe and is
not. Removing the whole stripe first means the PAN scan never sees it.

Then PANs, then the site's own rules. Site rules run last so that a site can add
a rule without having to reason about whether it fires before or after the card
handling. Their patterns see text that already has the card data out of it.

**This pass fails closed.** If anything in it raises, the record does not get a
partially redacted message; it gets refused, and the caller must not ship it. A
record that skipped redaction because of a bug is a cardholder data leak, and
the only safe response to not knowing is to keep it on the box.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field, replace

from stanchion.errors import RedactionError
from stanchion.records import LogRecord
from stanchion.redact.pan import find_pans, mask_pans
from stanchion.redact.rules import Rule, RuleHit, apply_rule
from stanchion.redact.track import find_secrets, strip_secrets

REDACTED_LABEL = "redacted"
CARD_RULE = "card_pan"
STRIPE_RULE = "card_stripe"


@dataclass(slots=True)
class RedactionAudit:
    """What the pass removed, counted by rule name.

    Counts only. The whole point is that nothing which was removed is written
    down anywhere, so the audit records that a thing happened and never what.
    """

    records: int = 0
    changed: int = 0
    hits: dict[str, int] = field(default_factory=dict)
    refused: int = 0

    def note(self, rule: str, count: int = 1) -> None:
        if count:
            self.hits[rule] = self.hits.get(rule, 0) + count

    @property
    def total_hits(self) -> int:
        return sum(self.hits.values())

    def as_dict(self) -> dict[str, int]:
        merged = {"records": self.records, "changed": self.changed, "refused": self.refused}
        merged.update(dict(sorted(self.hits.items())))
        return merged


@dataclass(frozen=True, slots=True)
class RedactionResult:
    """One line, after the pass."""

    text: str
    hits: tuple[RuleHit, ...] = ()
    stripes: int = 0
    pans: int = 0

    @property
    def changed(self) -> bool:
        return bool(self.hits) or bool(self.stripes) or bool(self.pans)


@dataclass(slots=True)
class Redactor:
    """Runs the fixed card rules and then the site's own."""

    rules: list[Rule] = field(default_factory=list)
    keep_first: int = 6
    keep_last: int = 4
    audit: RedactionAudit = field(default_factory=RedactionAudit)

    def redact_text(self, text: str) -> RedactionResult:
        """Run the whole pass over one line."""
        try:
            stripes = len(find_secrets(text))
            working = strip_secrets(text)
            pans = len(find_pans(working))
            working = mask_pans(working, self.keep_first, self.keep_last)
            hits: list[RuleHit] = []
            for rule in self.rules:
                working, caught = apply_rule(rule, working)
                hits.extend(caught)
        except (re.error, ValueError, TypeError) as exc:
            self.audit.refused += 1
            raise RedactionError("redaction failed, holding the record", detail=str(exc)) from exc
        self.audit.note(STRIPE_RULE, stripes)
        self.audit.note(CARD_RULE, pans)
        for hit in hits:
            self.audit.note(hit.rule)
        return RedactionResult(text=working, hits=tuple(hits), stripes=stripes, pans=pans)

    def redact(self, record: LogRecord) -> LogRecord:
        """Redact a record's message, labelling it when anything went."""
        self.audit.records += 1
        found = self.redact_text(record.message)
        if not found.changed:
            return record
        self.audit.changed += 1
        labels = dict(record.labels)
        labels[REDACTED_LABEL] = str(found.stripes + found.pans + len(found.hits))
        return replace(record, message=found.text, labels=labels)

    def redact_all(self, records: list[LogRecord]) -> list[LogRecord]:
        return [self.redact(record) for record in records]
