"""Breaking a filter expression into tokens.

The language exists because the alternative is a support engineer typing SQL
against the archive over a VPN at two in the morning, and the archive is the one
file on the box that must not be experimented on. A filter that cannot express
``DELETE`` is worth a parser.

It is written to be typed by hand:

    unit = payment-kernel and message ~ declined
    class = payment and since 2h and not message ~ heartbeat
    unit in (pump-bridge, tank-gauge) and stream = stderr

So bare words are values. ``payment-kernel`` needs no quotes even though it has
a dash in it, because quoting is the thing people forget and a language that
punishes them for it gets used through grep instead.

Every token keeps the position it came from. A parse error that says only "bad
syntax" is useless on a long expression, and the column is what turns it into a
message somebody can act on.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import StrEnum

from stanchion.errors import QueryError


class Kind(StrEnum):
    """What a token is."""

    WORD = "word"
    STRING = "string"
    NUMBER = "number"
    OPERATOR = "operator"
    LPAREN = "lparen"
    RPAREN = "rparen"
    COMMA = "comma"
    END = "end"


KEYWORDS = frozenset({"and", "or", "not", "in", "since", "until", "limit"})

OPERATORS = ("!~", "!=", ">=", "<=", "~", "=", ">", "<")

WORD_PATTERN = re.compile(r"[A-Za-z_][A-Za-z0-9_.:\-]*")
# A token that opens with a digit runs to the end of the run, letters
# included, so that "2h", "250ms" and "2025-03-04T12:00:00Z" are each one
# token. Splitting "2h" into a number and a word would make the parser
# responsible for gluing them back together with the spacing rules of the
# original text, which it no longer has.
NUMBER_PATTERN = re.compile(r"\d[A-Za-z0-9.:\-+]*")


@dataclass(frozen=True, slots=True)
class Token:
    """One token and where it was."""

    kind: Kind
    text: str
    position: int

    @property
    def is_keyword(self) -> bool:
        return self.kind is Kind.WORD and self.text.lower() in KEYWORDS

    @property
    def lower(self) -> str:
        return self.text.lower()

    def __str__(self) -> str:
        return f"{self.kind.value} {self.text!r} at {self.position}"


def _read_string(text: str, start: int) -> tuple[str, int]:
    """Read a quoted value, honouring a backslash escape."""
    quote = text[start]
    out: list[str] = []
    index = start + 1
    while index < len(text):
        character = text[index]
        if character == "\\" and index + 1 < len(text):
            out.append(text[index + 1])
            index += 2
            continue
        if character == quote:
            return "".join(out), index + 1
        out.append(character)
        index += 1
    raise QueryError("unclosed quote", detail=f"at {start}")


def tokenize(text: str) -> list[Token]:
    """Break an expression into tokens, ending with an END token."""
    tokens: list[Token] = []
    index = 0
    while index < len(text):
        character = text[index]
        if character.isspace():
            index += 1
            continue
        if character in "\"'":
            value, index = _read_string(text, index)
            tokens.append(Token(kind=Kind.STRING, text=value, position=index))
            continue
        if character == "(":
            tokens.append(Token(kind=Kind.LPAREN, text="(", position=index))
            index += 1
            continue
        if character == ")":
            tokens.append(Token(kind=Kind.RPAREN, text=")", position=index))
            index += 1
            continue
        if character == ",":
            tokens.append(Token(kind=Kind.COMMA, text=",", position=index))
            index += 1
            continue
        operator = _match_operator(text, index)
        if operator is not None:
            tokens.append(Token(kind=Kind.OPERATOR, text=operator, position=index))
            index += len(operator)
            continue
        word = WORD_PATTERN.match(text, index)
        if word is not None:
            tokens.append(Token(kind=Kind.WORD, text=word.group(0), position=index))
            index = word.end()
            continue
        number = NUMBER_PATTERN.match(text, index)
        if number is not None:
            tokens.append(Token(kind=Kind.NUMBER, text=number.group(0), position=index))
            index = number.end()
            continue
        raise QueryError("unexpected character", detail=f"{character!r} at {index}")
    tokens.append(Token(kind=Kind.END, text="", position=len(text)))
    return tokens


def _match_operator(text: str, index: int) -> str | None:
    for operator in OPERATORS:
        if text.startswith(operator, index):
            return operator
    return None


def describe(tokens: list[Token]) -> str:
    """A one line rendering, used by the error messages and the tests."""
    return " ".join(token.text for token in tokens if token.kind is not Kind.END)
