"""Turning tokens into a tree.

Recursive descent, three levels of precedence: ``or`` binds loosest, then
``and``, then ``not``. That is the order everybody already expects, so an
expression written without parentheses does what the person who typed it meant.

``and`` is also implied between two adjacent terms. ``unit = x since 2h`` reads
naturally and there is no other sensible meaning for two conditions side by
side, so the parser accepts it. Being strict there would buy nothing except a
second thing to get wrong at two in the morning.

Times are read here rather than in the evaluator, because ``since 2h`` means two
hours before *now* and the tree has to be able to say what instant that was. A
tree that carried the words instead would give different answers depending on
when it was walked, and a report would not be reproducible.
"""

from __future__ import annotations

from dataclasses import dataclass

from stanchion.errors import ConfigError, QueryError
from stanchion.query.ast import And, Compare, InSet, Node, Not, Op, Or, Query, Since, Until
from stanchion.query.lexer import Kind, Token, tokenize
from stanchion.timestamps import NS_PER_SECOND, NanoTime
from stanchion.units import parse_duration

VALUE_KINDS = frozenset({Kind.WORD, Kind.STRING, Kind.NUMBER})


def parse_instant(text: str, now: NanoTime) -> int:
    """Read either a duration back from now, or an absolute timestamp."""
    try:
        return NanoTime.parse(text).epoch_ns
    except QueryError:
        pass
    except Exception:  # noqa: BLE001 - a ParseError means it is not a timestamp
        pass
    try:
        window = parse_duration(text)
    except ConfigError as exc:
        raise QueryError("not a time or a duration", detail=text) from exc
    return now.shifted(-int(window.total_seconds() * NS_PER_SECOND)).epoch_ns


@dataclass(slots=True)
class Parser:
    """One expression, one parser."""

    tokens: list[Token]
    now: NanoTime
    index: int = 0

    @property
    def current(self) -> Token:
        return self.tokens[self.index]

    def advance(self) -> Token:
        token = self.current
        if token.kind is not Kind.END:
            self.index += 1
        return token

    def expect(self, kind: Kind, what: str) -> Token:
        if self.current.kind is not kind:
            raise QueryError(f"expected {what}", detail=str(self.current))
        return self.advance()

    def at_keyword(self, word: str) -> bool:
        return self.current.is_keyword and self.current.lower == word

    def parse(self) -> Query:
        # "limit 5" on its own is a whole query. Without this check the limit
        # keyword would be read as a field name and refused, which is a strange
        # thing to say about a word the language defines.
        empty = self.current.kind is Kind.END or self.at_keyword("limit")
        where = None if empty else self.parse_or()
        limit = self.parse_limit()
        if self.current.kind is not Kind.END:
            raise QueryError("unexpected trailing input", detail=str(self.current))
        return Query(where=where, limit=limit)

    def parse_limit(self) -> int | None:
        if not self.at_keyword("limit"):
            return None
        self.advance()
        token = self.current
        if token.kind is not Kind.NUMBER or not token.text.isdigit():
            raise QueryError("limit needs a whole number", detail=str(token))
        self.advance()
        value = int(token.text)
        if value < 1:
            raise QueryError("limit must be at least one", detail=token.text)
        return value

    def parse_or(self) -> Node:
        left = self.parse_and()
        while self.at_keyword("or"):
            self.advance()
            left = Or(left=left, right=self.parse_and())
        return left

    def parse_and(self) -> Node:
        left = self.parse_unary()
        while True:
            if self.at_keyword("and"):
                self.advance()
                left = And(left=left, right=self.parse_unary())
                continue
            if self._starts_a_term():
                left = And(left=left, right=self.parse_unary())
                continue
            return left

    def _starts_a_term(self) -> bool:
        """True when the next token could begin another condition."""
        token = self.current
        if token.kind is Kind.LPAREN:
            return True
        if token.kind is not Kind.WORD:
            return False
        if token.is_keyword:
            return token.lower in {"not", "since", "until"}
        return True

    def parse_unary(self) -> Node:
        if self.at_keyword("not"):
            self.advance()
            return Not(inner=self.parse_unary())
        return self.parse_primary()

    def parse_primary(self) -> Node:
        if self.current.kind is Kind.LPAREN:
            self.advance()
            inner = self.parse_or()
            self.expect(Kind.RPAREN, "a closing parenthesis")
            return inner
        if self.at_keyword("since"):
            self.advance()
            token = self._value_token()
            return Since(at_ns=parse_instant(token.text, self.now), written=token.text)
        if self.at_keyword("until"):
            self.advance()
            token = self._value_token()
            return Until(at_ns=parse_instant(token.text, self.now), written=token.text)
        return self.parse_comparison()

    def parse_comparison(self) -> Node:
        field = self.current
        if field.kind is not Kind.WORD or field.is_keyword:
            raise QueryError("expected a field name", detail=str(field))
        self.advance()
        if self.at_keyword("in"):
            self.advance()
            return InSet(field=field.text, values=self._value_list())
        operator = self.current
        if operator.kind is not Kind.OPERATOR:
            raise QueryError("expected a comparison operator", detail=str(operator))
        self.advance()
        value = self._value_token()
        return Compare(field=field.text, op=Op(operator.text), value=value.text)

    def _value_token(self) -> Token:
        token = self.current
        if token.kind not in VALUE_KINDS:
            raise QueryError("expected a value", detail=str(token))
        return self.advance()

    def _value_list(self) -> tuple[str, ...]:
        self.expect(Kind.LPAREN, "an opening parenthesis")
        values = [self._value_token().text]
        while self.current.kind is Kind.COMMA:
            self.advance()
            values.append(self._value_token().text)
        self.expect(Kind.RPAREN, "a closing parenthesis")
        return tuple(values)


def parse_query(text: str, now: NanoTime) -> Query:
    """Parse a whole filter expression."""
    return Parser(tokens=tokenize(text), now=now).parse()
