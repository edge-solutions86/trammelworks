from __future__ import annotations

import pytest

from stanchion.redact.track import (
    SecretKind,
    find_keyed_secrets,
    find_secrets,
    find_track1,
    find_track2,
    strip_secrets,
)

VISA = "4111111111111111"
TRACK1 = "%B4111111111111111^SMITH/JOHN            ^27031011000000000000000?"
TRACK2 = ";4111111111111111=27031011234567890?"
BARE2 = "4111111111111111=270310112345"


def test_a_track_one_image_is_found() -> None:
    found = find_track1(f"reader error raw={TRACK1}")
    assert len(found) == 1
    assert found[0].kind is SecretKind.TRACK1


def test_the_track_one_span_covers_the_whole_image() -> None:
    line = f"raw={TRACK1} end"
    found = find_track1(line)[0]
    assert line[found.start : found.end] == TRACK1


def test_a_line_without_a_stripe_has_no_track_one() -> None:
    assert find_track1("nozzle 1 lifted") == []


def test_a_track_two_image_with_sentinels_is_found() -> None:
    found = find_track2(f"swipe {TRACK2}")
    assert len(found) == 1
    assert found[0].kind is SecretKind.TRACK2


def test_a_bare_track_two_is_found() -> None:
    found = find_track2(f"swipe {BARE2} declined")
    assert len(found) == 1
    assert found[0].text == BARE2


def test_a_bare_track_two_needs_a_real_pan() -> None:
    assert find_track2("counter 1234567890123456789=27031011") == []


def test_a_config_line_with_an_equals_sign_is_not_a_stripe() -> None:
    assert find_track2("max_line_bytes=1048576") == []


def test_a_sentinel_track_is_not_also_reported_as_bare() -> None:
    assert len(find_track2(f"raw={TRACK2}")) == 1


def test_two_stripes_on_one_line_are_both_found() -> None:
    assert len(find_track2(f"{BARE2} and {BARE2}")) == 2


@pytest.mark.parametrize(
    "line",
    [
        "pinblock=1A2B3C4D5E6F7081",
        "PIN_BLOCK: 1A2B3C4D5E6F7081",
        'encrypted_pin="1A2B3C4D5E6F7081"',
        "pinblk = 1A2B3C4D5E6F7081",
    ],
)
def test_a_pin_block_is_found_by_its_key(line: str) -> None:
    found = find_keyed_secrets(line)
    assert len(found) == 1
    assert found[0].kind is SecretKind.PIN_BLOCK


@pytest.mark.parametrize("line", ["cvv=123", "CVC2: 456", "csc=789", "cav2=4321"])
def test_a_card_code_is_found_by_its_key(line: str) -> None:
    found = find_keyed_secrets(line)
    assert len(found) == 1
    assert found[0].kind is SecretKind.CARD_CODE


def test_the_span_covers_the_value_not_the_key() -> None:
    line = "cvv=123 approved"
    found = find_keyed_secrets(line)[0]
    assert line[found.start : found.end] == "123"


def test_a_quoted_value_has_its_quotes_left_in_place() -> None:
    line = 'cvv="123"'
    found = find_keyed_secrets(line)[0]
    assert line[found.start : found.end] == "123"


def test_sixteen_hex_characters_without_a_key_are_left_alone() -> None:
    assert find_keyed_secrets("container 1a2b3c4d5e6f7081 started") == []


def test_a_key_that_is_not_a_secret_is_left_alone() -> None:
    assert find_keyed_secrets("pump=1A2B3C4D") == []


def test_find_secrets_returns_everything_in_order() -> None:
    line = f"cvv=123 then {BARE2}"
    kinds = [found.kind for found in find_secrets(line)]
    assert kinds == [SecretKind.CARD_CODE, SecretKind.TRACK2]


def test_overlapping_matches_keep_the_longest() -> None:
    line = f"raw={TRACK1}"
    found = find_secrets(line)
    assert len(found) == 1
    assert found[0].kind is SecretKind.TRACK1


def test_strip_replaces_a_track_two() -> None:
    assert strip_secrets(f"swipe {BARE2} declined") == "swipe [redacted] declined"


def test_strip_replaces_a_track_one() -> None:
    assert strip_secrets(f"raw={TRACK1}") == "raw=[redacted]"


def test_strip_replaces_a_pin_block_but_keeps_the_key() -> None:
    assert strip_secrets("pinblock=1A2B3C4D5E6F7081") == "pinblock=[redacted]"


def test_strip_replaces_a_cvv_but_keeps_the_key() -> None:
    assert strip_secrets("auth cvv=123 amount=4000") == "auth cvv=[redacted] amount=4000"


def test_strip_leaves_a_clean_line_alone() -> None:
    line = "nozzle 1 lifted on pump 3"
    assert strip_secrets(line) == line


def test_strip_takes_a_replacement_string() -> None:
    assert strip_secrets("cvv=123", replacement="XXX") == "cvv=XXX"


def test_strip_handles_two_secrets_on_one_line() -> None:
    assert strip_secrets(f"cvv=123 raw={BARE2}") == "cvv=[redacted] raw=[redacted]"


def test_stripping_is_idempotent() -> None:
    once = strip_secrets(f"raw={BARE2}")
    assert strip_secrets(once) == once


def test_a_stripe_is_removed_whole_rather_than_masked() -> None:
    stripped = strip_secrets(f"raw={BARE2}")
    assert "4111" not in stripped
    assert "2703" not in stripped
