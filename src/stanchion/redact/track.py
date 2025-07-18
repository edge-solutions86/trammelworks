"""Magnetic stripe data, PIN blocks and the three digit codes.

A PAN is the famous one, but on a forecourt the worse leak is track data. A
dispenser reader that fails to parse a swipe often prints the whole stripe as a
diagnostic, and a track 2 image contains the PAN, the expiry and the service
code together, which is enough to clone the card. It must never be stored, not
even masked, so unlike a PAN this is replaced wholesale.

The formats:

* **track 1**, ``%B<pan>^<surname>/<forename>^<yymm><service><discretionary>?``
* **track 2**, ``;<pan>=<yymm><service><discretionary>?``, and the sentinels are
  frequently missing because whatever printed it stripped them

Track 2 without its sentinels is just a PAN, an equals sign and digits, and that
is the form that actually shows up. The PAN part still has to satisfy the usual
tests, which is what stops an equals sign in a config dump being read as a
stripe.

PIN blocks and card verification codes are found by their key name rather than
their shape. An ISO format 0 PIN block is sixteen hex characters, which is also
what half the identifiers on the box look like, so shape alone would redact the
world. If a line does not say what the value is, we do not guess.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import StrEnum

from stanchion.redact.luhn import digits_only
from stanchion.redact.pan import looks_like_pan

TRACK1 = re.compile(r"%[A-Z]\d{12,19}\^[^^]{0,30}\^\d{4}[^?]{0,60}\??")
TRACK2_SENTINEL = re.compile(r";(\d{12,19})=(\d{4}[0-9A-Za-z]{0,50})\??")
TRACK2_BARE = re.compile(r"(?<!\d)(\d{12,19})=(\d{4}\d{0,50})(?!\d)")

KEYED_SECRETS = re.compile(
    r"(?i)\b(pin_?block|pinblk|encrypted_?pin|cvv2?|cvc2?|cid|cav2?|csc)"
    r"\s*[=:]\s*(\"?)([0-9A-Fa-f]{3,32})\2"
)

REPLACEMENT = "[redacted]"


class SecretKind(StrEnum):
    """What was found, which is what the audit trail counts."""

    TRACK1 = "track1"
    TRACK2 = "track2"
    PIN_BLOCK = "pin_block"
    CARD_CODE = "card_code"


PIN_KEYS = frozenset({"pin_block", "pinblock", "pinblk", "encrypted_pin", "encryptedpin"})


@dataclass(frozen=True, slots=True)
class SecretMatch:
    """Something that must not leave the site, and where it was."""

    start: int
    end: int
    kind: SecretKind
    text: str


def _kind_for_key(key: str) -> SecretKind:
    return SecretKind.PIN_BLOCK if key.lower() in PIN_KEYS else SecretKind.CARD_CODE


def find_track1(text: str) -> list[SecretMatch]:
    """Track 1 images, which carry the cardholder name as well."""
    return [
        SecretMatch(
            start=match.start(), end=match.end(), kind=SecretKind.TRACK1, text=match.group(0)
        )
        for match in TRACK1.finditer(text)
    ]


def find_track2(text: str) -> list[SecretMatch]:
    """Track 2 images, with or without their sentinels."""
    found: list[SecretMatch] = []
    taken: list[tuple[int, int]] = []
    for match in TRACK2_SENTINEL.finditer(text):
        if not looks_like_pan(digits_only(match.group(1))):
            continue
        found.append(
            SecretMatch(
                start=match.start(), end=match.end(), kind=SecretKind.TRACK2, text=match.group(0)
            )
        )
        taken.append((match.start(), match.end()))
    for match in TRACK2_BARE.finditer(text):
        if any(start <= match.start() < end for start, end in taken):
            continue
        if not looks_like_pan(digits_only(match.group(1))):
            continue
        found.append(
            SecretMatch(
                start=match.start(), end=match.end(), kind=SecretKind.TRACK2, text=match.group(0)
            )
        )
    return sorted(found, key=lambda item: item.start)


def find_keyed_secrets(text: str) -> list[SecretMatch]:
    """PIN blocks and card codes, found by the name beside them."""
    found: list[SecretMatch] = []
    for match in KEYED_SECRETS.finditer(text):
        value_start = match.start(3)
        found.append(
            SecretMatch(
                start=value_start,
                end=match.end(3),
                kind=_kind_for_key(match.group(1)),
                text=match.group(3),
            )
        )
    return found


def find_secrets(text: str) -> list[SecretMatch]:
    """Everything that has to go, ordered and non overlapping."""
    everything = find_track1(text) + find_track2(text) + find_keyed_secrets(text)
    everything.sort(key=lambda item: (item.start, -(item.end - item.start)))
    kept: list[SecretMatch] = []
    cursor = -1
    for item in everything:
        if item.start < cursor:
            continue
        kept.append(item)
        cursor = item.end
    return kept


def strip_secrets(text: str, replacement: str = REPLACEMENT) -> str:
    """Replace every stripe image, PIN block and card code."""
    out: list[str] = []
    cursor = 0
    for found in find_secrets(text):
        out.append(text[cursor : found.start])
        out.append(replacement)
        cursor = found.end
    out.append(text[cursor:])
    return "".join(out)
