from __future__ import annotations

import pytest
from hypothesis import given
from hypothesis import strategies as st

from stanchion.redact.luhn import (
    digits_only,
    luhn_check_digit,
    luhn_sum,
    luhn_valid,
    plausible_length,
)
from stanchion.redact.pan import brand_of, find_pans, looks_like_pan, mask_digits, mask_pans

VISA = "4111111111111111"
MASTERCARD = "5500005555555559"
AMEX = "378282246310005"
FLEET = "7083280000000000000"


def test_a_known_visa_passes_luhn() -> None:
    assert luhn_valid(VISA) is True


def test_changing_one_digit_breaks_luhn() -> None:
    assert luhn_valid("4111111111111112") is False


def test_luhn_needs_digits() -> None:
    assert luhn_valid("41111111111111 1") is False
    assert luhn_valid("") is False


def test_luhn_sum_refuses_a_non_digit_string() -> None:
    with pytest.raises(ValueError, match="non empty digit string"):
        luhn_sum("41a1")


def test_the_check_digit_completes_a_body() -> None:
    body = VISA[:-1]
    assert luhn_check_digit(body) == VISA[-1]
    assert luhn_valid(body + luhn_check_digit(body)) is True


@given(st.text(alphabet="0123456789", min_size=1, max_size=18))
def test_any_body_can_be_completed(body: str) -> None:
    assert luhn_valid(body + luhn_check_digit(body)) is True


def test_leading_zeros_are_kept() -> None:
    body = "0000000000000"
    assert luhn_valid(body + luhn_check_digit(body)) is True


def test_digits_only_strips_the_separators_a_terminal_prints() -> None:
    assert digits_only("4111 1111-1111 1111") == VISA


def test_plausible_length_covers_thirteen_to_nineteen() -> None:
    assert plausible_length("4" * 12) is False
    assert plausible_length("4" * 13) is True
    assert plausible_length("4" * 19) is True
    assert plausible_length("4" * 20) is False


@pytest.mark.parametrize(
    ("digits", "brand"),
    [
        (VISA, "visa"),
        (MASTERCARD, "mastercard"),
        (AMEX, "amex"),
        ("6011000990139424", "discover"),
        ("3530111333300000", "jcb"),
        ("6759649826438453", "maestro"),
    ],
)
def test_the_brand_is_read_from_the_prefix(digits: str, brand: str) -> None:
    assert brand_of(digits) == brand


def test_a_fleet_prefix_is_recognised() -> None:
    assert brand_of(FLEET) == "fleet"


def test_the_longest_matching_prefix_wins() -> None:
    assert brand_of("7081380000000000") == "fleet"
    assert brand_of("7071380000000000") == "wex"


def test_an_unknown_prefix_has_no_brand() -> None:
    assert brand_of("9999999999999999") is None


def test_all_three_tests_must_pass() -> None:
    assert looks_like_pan(VISA) is True
    assert looks_like_pan("4111111111111112") is False
    assert looks_like_pan("41111111111") is False


def test_a_luhn_valid_number_with_no_issuer_is_not_a_pan() -> None:
    body = "999999999999"
    number = body + luhn_check_digit(body)
    assert luhn_valid(number) is True
    assert looks_like_pan(number) is False


def test_a_plain_pan_is_found() -> None:
    found = find_pans(f"auth request pan={VISA} amount=4000")
    assert len(found) == 1
    assert found[0].digits == VISA
    assert found[0].brand == "visa"


def test_a_grouped_pan_is_found() -> None:
    found = find_pans("pan=4111 1111 1111 1111 approved")
    assert len(found) == 1
    assert found[0].digits == VISA
    assert found[0].text == "4111 1111 1111 1111"


def test_a_dash_separated_pan_is_found() -> None:
    assert find_pans("pan=4111-1111-1111-1111")[0].digits == VISA


def test_the_span_covers_exactly_the_number() -> None:
    line = f"pan={VISA} end"
    found = find_pans(line)[0]
    assert line[found.start : found.end] == VISA


def test_two_pans_on_one_line_are_both_found() -> None:
    found = find_pans(f"from {VISA} to {MASTERCARD}")
    assert [item.brand for item in found] == ["visa", "mastercard"]


def test_a_short_number_is_not_a_pan() -> None:
    assert find_pans("terminal 123456789012") == []


def test_a_transaction_reference_is_left_alone() -> None:
    assert find_pans("txn ref 9182736455647382") == []


def test_a_tank_volume_is_left_alone() -> None:
    assert find_pans("tank 3 volume 41200456 ml") == []


def test_an_amex_of_fifteen_digits_is_found() -> None:
    assert find_pans(f"pan={AMEX}")[0].length == 15


def test_a_nineteen_digit_fleet_card_is_found() -> None:
    found = find_pans(f"card {FLEET} accepted")
    assert found and found[0].digits == FLEET


def test_a_pan_run_together_with_an_expiry_is_still_found() -> None:
    found = find_pans(f"{VISA}2703")
    assert found
    assert found[0].digits == VISA


def test_the_trimmed_span_stops_before_the_expiry() -> None:
    line = f"track {VISA}2703 end"
    found = find_pans(line)[0]
    assert line[found.start : found.end] == VISA


def test_mask_keeps_the_first_six_and_last_four() -> None:
    assert mask_digits(VISA) == "411111******1111"


def test_mask_can_keep_nothing() -> None:
    assert mask_digits(VISA, keep_first=0, keep_last=0) == "*" * 16


def test_mask_of_a_short_number_hides_everything() -> None:
    assert mask_digits("41111111111", keep_first=6, keep_last=4) == "*" * 11


def test_a_mask_that_would_hide_too_little_hides_it_all() -> None:
    # Thirteen digits keeping six and four leaves three hidden, which is not a
    # mask, it is a hint. Everything goes instead.
    assert mask_digits("4" * 13) == "*" * 13


def test_fifteen_digits_still_keep_the_head_and_tail() -> None:
    assert mask_digits(AMEX) == "378282*****0005"


def test_mask_refuses_a_negative_keep() -> None:
    with pytest.raises(ValueError, match="negative number of digits"):
        mask_digits(VISA, keep_first=-1)


def test_mask_pans_replaces_in_place() -> None:
    assert mask_pans(f"auth pan={VISA} amount=4000") == "auth pan=411111******1111 amount=4000"


def test_mask_pans_drops_the_separators_with_the_digits() -> None:
    assert mask_pans("pan=4111 1111 1111 1111 ok") == "pan=411111******1111 ok"


def test_mask_pans_handles_two_on_one_line() -> None:
    masked = mask_pans(f"{VISA} then {MASTERCARD}")
    assert masked == "411111******1111 then 550000******5559"


def test_mask_pans_leaves_a_clean_line_alone() -> None:
    line = "nozzle 1 lifted on pump 3"
    assert mask_pans(line) == line


def test_mask_pans_leaves_a_reference_alone() -> None:
    line = "txn ref 9182736455647382 settled"
    assert mask_pans(line) == line


def test_masking_is_idempotent() -> None:
    once = mask_pans(f"pan={VISA}")
    assert mask_pans(once) == once
