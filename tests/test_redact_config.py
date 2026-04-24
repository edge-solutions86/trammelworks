from __future__ import annotations

from typing import Any

import pytest

from stanchion.errors import ConfigError
from stanchion.redact.config import RedactConfig, redact_from_mapping, summarize
from stanchion.redact.rules import Rule


def test_the_defaults_are_on_and_pci_shaped() -> None:
    built = RedactConfig()
    assert built.enabled is True
    assert built.keep_first == 6
    assert built.keep_last == 4


def test_keeping_more_than_six_leading_digits_is_refused() -> None:
    with pytest.raises(ConfigError, match="more than six leading"):
        RedactConfig(keep_first=7)


def test_keeping_more_than_four_trailing_digits_is_refused() -> None:
    with pytest.raises(ConfigError, match="more than four trailing"):
        RedactConfig(keep_last=5)


def test_keeping_fewer_is_allowed() -> None:
    assert RedactConfig(keep_first=0, keep_last=0).keep_first == 0


def test_a_negative_keep_is_refused() -> None:
    with pytest.raises(ConfigError, match="negative number of digits"):
        RedactConfig(keep_first=-1)


def test_two_rules_may_not_share_a_name() -> None:
    twice = (Rule(name="a", pattern="x"), Rule(name="a", pattern="y"))
    with pytest.raises(ConfigError, match="share a name"):
        RedactConfig(rules=twice)


def test_the_rule_names_are_listed() -> None:
    built = RedactConfig(rules=(Rule(name="driver", pattern="x"),))
    assert built.rule_names == ("driver",)


def test_an_enabled_pass_is_fine_anywhere() -> None:
    RedactConfig().check_site("US-TX-0417")


def test_disabling_the_pass_on_a_real_site_is_refused() -> None:
    with pytest.raises(ConfigError, match="only be disabled on a lab site"):
        RedactConfig(enabled=False).check_site("US-TX-0417")


@pytest.mark.parametrize("site", ["XX-LAB-0001", "XX-DEV-0009"])
def test_disabling_the_pass_on_a_bench_is_allowed(site: str) -> None:
    RedactConfig(enabled=False).check_site(site)


def test_the_refusal_names_the_site() -> None:
    with pytest.raises(ConfigError) as caught:
        RedactConfig(enabled=False).check_site("GB-LDN-0009")
    assert caught.value.detail == "GB-LDN-0009"


def test_the_summary_of_the_default_mentions_the_card_rules() -> None:
    assert str(summarize(RedactConfig())) == "redaction on, keep 6+4, card rules only"


def test_the_summary_counts_site_rules() -> None:
    built = RedactConfig(rules=(Rule(name="driver", pattern="x"),))
    assert str(summarize(built)) == "redaction on, keep 6+4, plus 1 site rules"


def test_a_disabled_summary_shouts() -> None:
    assert str(summarize(RedactConfig(enabled=False))) == "redaction DISABLED"


def test_the_summary_carries_the_rule_names() -> None:
    built = RedactConfig(rules=(Rule(name="driver", pattern="x"),))
    assert summarize(built).rules == ("driver",)


def test_the_mapping_builder_reads_a_full_section() -> None:
    raw: dict[str, Any] = {
        "keep_first": 0,
        "keep_last": 4,
        "rules": {"driver_id": {"pattern": r"driver=(\d+)"}},
    }
    built = redact_from_mapping(raw)
    assert built.keep_first == 0
    assert built.rule_names == ("driver_id",)


def test_an_empty_section_gives_the_defaults() -> None:
    assert redact_from_mapping({}) == RedactConfig()


def test_the_mapping_builder_rejects_an_unknown_key() -> None:
    with pytest.raises(ConfigError, match="unknown redact key"):
        redact_from_mapping({"keep_middle": 2})


def test_the_mapping_builder_needs_rules_to_be_a_mapping() -> None:
    with pytest.raises(ConfigError, match="the rules section must be a mapping"):
        redact_from_mapping({"rules": ["driver"]})


def test_the_mapping_builder_can_turn_the_pass_off() -> None:
    assert redact_from_mapping({"enabled": False}).enabled is False


def test_a_broken_rule_in_the_section_is_refused() -> None:
    with pytest.raises(ConfigError, match="does not compile"):
        redact_from_mapping({"rules": {"bad": {"pattern": "(unclosed"}}})
