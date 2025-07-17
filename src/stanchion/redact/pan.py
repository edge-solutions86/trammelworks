"""Finding card numbers in a line of log output.

Three tests have to pass together before a run of digits is treated as a PAN,
and each one on its own is useless:

* **length**, 13 to 19 digits
* **Luhn**, which one number in ten passes by chance
* **an issuer prefix we recognise**, which is what turns "passes Luhn" into
  "looks like a card". A forecourt controller prints a lot of long numbers:
  transaction references, EMV tags, tank volumes in millilitres.

Fuel cards are why the prefix table is not the usual four brands. A site takes
more fleet cards than credit cards, and those live in ranges the public examples
never mention. Getting that wrong is not a tidy false negative: an unrecognised
fleet PAN ships off site in the clear.

The scan runs on the raw text and keeps byte spans, because the replacement has
to go back exactly where the digits were. Separators inside a group are allowed,
because terminals print ``4111 1111 1111 1111``, but a separator run of more
than one character ends the candidate.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from stanchion.redact.luhn import digits_only, luhn_valid, plausible_length

#: Issuer prefixes seen across the estate. The fuel ranges are the ones that
#: matter here and they are the ones a generic library will not have.
ISSUER_PREFIXES: dict[str, tuple[str, ...]] = {
    "visa": ("4",),
    "mastercard": ("51", "52", "53", "54", "55", "22", "23", "24", "25", "26", "27"),
    "amex": ("34", "37"),
    "discover": ("6011", "65", "644", "645", "646", "647", "648", "649"),
    "diners": ("300", "301", "302", "303", "304", "305", "36", "38"),
    "jcb": ("35",),
    "maestro": ("50", "56", "57", "58", "6759", "6761", "6762", "6763"),
    # Fleet and fuel. 7-prefixed ranges are private label cards issued by the
    # fuel brands themselves and they are the bulk of forecourt traffic.
    "fleet": ("7", "708", "70251", "70256"),
    "wex": ("690046", "707138"),
    "voyager": ("8699",),
}

CANDIDATE = re.compile(r"\d(?:[ -]?\d){11,22}")

MASK_CHARACTER = "*"
KEEP_FIRST = 6
KEEP_LAST = 4

#: A mask that hides only a digit or two is not a mask. If keeping the head and
#: tail would leave fewer than this many hidden, everything goes instead.
MIN_MASKED = 4


@dataclass(frozen=True, slots=True)
class PanMatch:
    """A card number found in a line, and where it was."""

    start: int
    end: int
    text: str
    digits: str
    brand: str

    @property
    def length(self) -> int:
        return len(self.digits)


def brand_of(digits: str) -> str | None:
    """The issuer whose range this number falls in, longest prefix wins."""
    best: tuple[int, str] | None = None
    for brand, prefixes in ISSUER_PREFIXES.items():
        for prefix in prefixes:
            if digits.startswith(prefix) and (best is None or len(prefix) > best[0]):
                best = (len(prefix), brand)
    return None if best is None else best[1]


def looks_like_pan(digits: str) -> bool:
    """All three tests, in the order that rejects fastest."""
    return plausible_length(digits) and luhn_valid(digits) and brand_of(digits) is not None


def find_pans(text: str) -> list[PanMatch]:
    """Every card number in a line, left to right and non overlapping."""
    found: list[PanMatch] = []
    for match in CANDIDATE.finditer(text):
        raw = match.group(0)
        digits = digits_only(raw)
        if looks_like_pan(digits):
            found.append(
                PanMatch(
                    start=match.start(),
                    end=match.end(),
                    text=raw,
                    digits=digits,
                    brand=brand_of(digits) or "unknown",
                )
            )
            continue
        trimmed = _trim_to_pan(raw, match.start())
        if trimmed is not None:
            found.append(trimmed)
    return found


def _trim_to_pan(raw: str, offset: int) -> PanMatch | None:
    """Try shorter prefixes of an over long run.

    A terminal that prints a PAN immediately followed by an expiry gives one run
    of twenty two digits. The whole run fails, but its first sixteen are a card,
    so the longest valid prefix is taken rather than giving up on the line.
    """
    positions = [index for index, character in enumerate(raw) if character.isdigit()]
    for count in range(len(positions), 12, -1):
        end = positions[count - 1] + 1
        candidate = raw[:end]
        digits = digits_only(candidate)
        if looks_like_pan(digits):
            return PanMatch(
                start=offset,
                end=offset + end,
                text=candidate,
                digits=digits,
                brand=brand_of(digits) or "unknown",
            )
    return None


def mask_digits(digits: str, keep_first: int = KEEP_FIRST, keep_last: int = KEEP_LAST) -> str:
    """Mask a PAN, keeping the parts the card schemes allow to be stored."""
    if keep_first < 0 or keep_last < 0:
        raise ValueError("cannot keep a negative number of digits")
    hidden = len(digits) - keep_first - keep_last
    if hidden < MIN_MASKED:
        return MASK_CHARACTER * len(digits)
    head = digits[:keep_first]
    tail = digits[len(digits) - keep_last :] if keep_last else ""
    return head + MASK_CHARACTER * hidden + tail


def mask_pans(text: str, keep_first: int = KEEP_FIRST, keep_last: int = KEEP_LAST) -> str:
    """Replace every card number in a line with its masked form."""
    out: list[str] = []
    cursor = 0
    for found in find_pans(text):
        out.append(text[cursor : found.start])
        out.append(mask_digits(found.digits, keep_first, keep_last))
        cursor = found.end
    out.append(text[cursor:])
    return "".join(out)
