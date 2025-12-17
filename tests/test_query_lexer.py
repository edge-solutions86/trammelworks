from __future__ import annotations

import pytest

from stanchion.errors import QueryError
from stanchion.query.lexer import Kind, Token, describe, tokenize


def kinds(text: str) -> list[Kind]:
    return [token.kind for token in tokenize(text)]


def texts(text: str) -> list[str]:
    return [token.text for token in tokenize(text) if token.kind is not Kind.END]


def test_an_empty_expression_is_just_the_end() -> None:
    assert kinds("") == [Kind.END]


def test_whitespace_alone_is_just_the_end() -> None:
    assert kinds("   \t ") == [Kind.END]


def test_a_bare_word_is_a_word() -> None:
    assert kinds("unit") == [Kind.WORD, Kind.END]


def test_a_dashed_value_needs_no_quotes() -> None:
    assert texts("unit = payment-kernel") == ["unit", "=", "payment-kernel"]


def test_a_dotted_label_name_is_one_word() -> None:
    assert texts("label.lane = 3") == ["label.lane", "=", "3"]


def test_every_comparison_operator_is_recognised() -> None:
    for operator in ("=", "!=", "~", "!~", ">", "<", ">=", "<="):
        assert texts(f"a {operator} b") == ["a", operator, "b"]


def test_the_two_character_operators_win_over_the_one_character_ones() -> None:
    assert texts("a >= b") == ["a", ">=", "b"]
    assert texts("a !~ b") == ["a", "!~", "b"]
    assert texts("a != b") == ["a", "!=", "b"]


def test_an_operator_needs_no_spaces_around_it() -> None:
    assert texts("unit=payment-kernel") == ["unit", "=", "payment-kernel"]


def test_a_double_quoted_string_is_one_token() -> None:
    tokens = tokenize('message ~ "auth declined"')
    assert tokens[2].kind is Kind.STRING
    assert tokens[2].text == "auth declined"


def test_a_single_quoted_string_works_too() -> None:
    assert tokenize("message ~ 'auth declined'")[2].text == "auth declined"


def test_a_quote_can_be_escaped_inside_a_string() -> None:
    assert tokenize('message ~ "say \\"no\\""')[2].text == 'say "no"'


def test_a_backslash_can_be_escaped() -> None:
    assert tokenize('message ~ "back\\\\slash"')[2].text == "back\\slash"


def test_an_unclosed_quote_is_refused() -> None:
    with pytest.raises(QueryError, match="unclosed quote"):
        tokenize('message ~ "never ends')


def test_an_empty_string_is_allowed() -> None:
    assert tokenize('message = ""')[2].text == ""


def test_parentheses_and_commas_are_their_own_tokens() -> None:
    assert kinds("in (a, b)") == [
        Kind.WORD,
        Kind.LPAREN,
        Kind.WORD,
        Kind.COMMA,
        Kind.WORD,
        Kind.RPAREN,
        Kind.END,
    ]


def test_a_number_is_a_number() -> None:
    tokens = tokenize("limit 40")
    assert tokens[1].kind is Kind.NUMBER
    assert tokens[1].text == "40"


def test_a_timestamp_reads_as_one_number_token() -> None:
    tokens = tokenize("since 2025-03-04T12:00:00Z")
    assert tokens[1].kind is Kind.NUMBER
    assert tokens[1].text == "2025-03-04T12:00:00Z"


def test_a_duration_is_one_token() -> None:
    tokens = tokenize("since 2h")
    assert tokens[1].kind is Kind.NUMBER
    assert tokens[1].text == "2h"


def test_a_compound_duration_is_one_token() -> None:
    assert tokenize("since 1d12h")[1].text == "1d12h"


def test_a_millisecond_duration_is_one_token() -> None:
    assert tokenize("since 250ms")[1].text == "250ms"


def test_the_keywords_are_recognised() -> None:
    for word in ("and", "or", "not", "in", "since", "until", "limit"):
        assert tokenize(word)[0].is_keyword is True


def test_a_field_name_is_not_a_keyword() -> None:
    assert tokenize("unit")[0].is_keyword is False


def test_keywords_are_matched_without_case() -> None:
    assert tokenize("AND")[0].is_keyword is True
    assert tokenize("And")[0].lower == "and"


def test_every_token_knows_where_it_was() -> None:
    tokens = tokenize("unit = pump-bridge")
    assert tokens[0].position == 0
    assert tokens[1].position == 5
    assert tokens[2].position == 7


def test_the_end_token_sits_at_the_end() -> None:
    text = "unit = x"
    assert tokenize(text)[-1].position == len(text)


def test_an_unexpected_character_is_refused() -> None:
    with pytest.raises(QueryError, match="unexpected character"):
        tokenize("unit = $x")


def test_the_refusal_names_the_position() -> None:
    with pytest.raises(QueryError) as caught:
        tokenize("unit = $x")
    assert caught.value.detail is not None
    assert "at 7" in caught.value.detail


def test_a_whole_expression_tokenizes() -> None:
    text = 'unit = payment-kernel and message ~ "declined" and since 2h'
    assert texts(text) == [
        "unit",
        "=",
        "payment-kernel",
        "and",
        "message",
        "~",
        "declined",
        "and",
        "since",
        "2h",
    ]


def test_describe_reads_the_expression_back() -> None:
    assert describe(tokenize("unit = x and y")) == "unit = x and y"


def test_a_token_renders_with_its_position() -> None:
    token = Token(kind=Kind.WORD, text="unit", position=4)
    assert str(token) == "word 'unit' at 4"
