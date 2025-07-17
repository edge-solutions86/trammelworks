"""The Luhn check.

Every payment card number ends in a check digit computed this way, so Luhn is
the cheapest filter there is between "sixteen digits" and "a card number". It is
a filter and not a decision: one number in ten passes by chance, so a sixteen
digit order reference has a one in ten chance of looking like a PAN. What Luhn
buys is the other nine in ten.

Written on the digit string rather than an integer on purpose. Leading zeros are
significant in a PAN, and ``int("0012...")`` throws them away.
"""

from __future__ import annotations

MIN_PAN_DIGITS = 13
MAX_PAN_DIGITS = 19


def digits_only(raw: str) -> str:
    """Strip the separators a terminal prints between groups."""
    return "".join(character for character in raw if character.isdigit())


def luhn_sum(digits: str) -> int:
    """The Luhn total for a digit string, check digit included."""
    if not digits or not digits.isdigit():
        raise ValueError("luhn needs a non empty digit string")
    total = 0
    for position, character in enumerate(reversed(digits)):
        value = ord(character) - ord("0")
        if position % 2 == 1:
            value *= 2
            if value > 9:
                value -= 9
        total += value
    return total


def luhn_valid(digits: str) -> bool:
    """True when the string carries a correct Luhn check digit."""
    if not digits.isdigit() or len(digits) < 2:
        return False
    return luhn_sum(digits) % 10 == 0


def luhn_check_digit(body: str) -> str:
    """The digit that would make ``body`` valid, used by the test corpus."""
    if not body.isdigit():
        raise ValueError("luhn needs a digit string")
    remainder = luhn_sum(body + "0") % 10
    return str((10 - remainder) % 10)


def plausible_length(digits: str) -> bool:
    """True for a length a real card number can have."""
    return MIN_PAN_DIGITS <= len(digits) <= MAX_PAN_DIGITS
