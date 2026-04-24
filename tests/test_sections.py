from __future__ import annotations

import pytest

from stanchion.archive.config import archive_settings_from_mapping
from stanchion.config import site_from_mapping
from stanchion.errors import ConfigError
from stanchion.ingest.multiline import rules_from_mapping
from stanchion.redact.config import redact_from_mapping
from stanchion.sections import check_keys, require, section, suggest
from stanchion.ship.config import ship_settings_from_mapping
from stanchion.spool.config import spool_settings_from_mapping

KNOWN = {"directory", "budget", "segment", "compress"}


def test_a_section_with_only_known_keys_passes() -> None:
    raw = {"budget": "1GiB", "compress": True}
    assert check_keys(raw, KNOWN, "spool") is raw


def test_an_empty_section_passes() -> None:
    assert check_keys({}, KNOWN, "spool") == {}


def test_an_unknown_key_is_refused() -> None:
    with pytest.raises(ConfigError, match="unknown spool key"):
        check_keys({"nonsense": 1}, KNOWN, "spool")


def test_the_section_is_named_in_the_message() -> None:
    with pytest.raises(ConfigError, match="unknown archive key"):
        check_keys({"nonsense": 1}, KNOWN, "archive")


def test_a_near_miss_gets_a_suggestion() -> None:
    with pytest.raises(ConfigError, match="did you mean budget"):
        check_keys({"budgt": "1GiB"}, KNOWN, "spool")


def test_a_key_that_is_nothing_like_one_gets_no_suggestion() -> None:
    with pytest.raises(ConfigError) as caught:
        check_keys({"xyzzy": 1}, KNOWN, "spool")
    assert "did you mean" not in str(caught.value)


def test_several_wrong_keys_are_all_named() -> None:
    with pytest.raises(ConfigError) as caught:
        check_keys({"one": 1, "two": 2}, KNOWN, "spool")
    assert "one, two" in str(caught.value)


def test_several_wrong_keys_get_no_suggestion() -> None:
    # Guessing at one of several is worse than not guessing: the engineer
    # fixes the one that was named and hits the same error again.
    with pytest.raises(ConfigError) as caught:
        check_keys({"budgt": 1, "segmnt": 2}, KNOWN, "spool")
    assert "did you mean" not in str(caught.value)


def test_the_wrong_keys_come_out_sorted() -> None:
    with pytest.raises(ConfigError) as caught:
        check_keys({"zeta": 1, "alpha": 2}, KNOWN, "spool")
    assert "alpha, zeta" in str(caught.value)


def test_a_suggestion_is_found_for_a_close_name() -> None:
    assert suggest("comress", KNOWN) == "compress"


def test_no_suggestion_for_a_distant_name() -> None:
    assert suggest("qqqq", KNOWN) == ""


def test_a_nested_block_comes_back() -> None:
    assert section({"retention": {"payment": "180d"}}, "retention", "archive") == {
        "payment": "180d"
    }


def test_a_missing_block_defaults_to_empty() -> None:
    assert section({}, "retention", "archive") == {}


def test_a_block_written_with_nothing_under_it_defaults_to_empty() -> None:
    # This is what YAML gives for a key with an empty body, and it is how
    # somebody writes "defaults please".
    assert section({"retention": None}, "retention", "archive") == {}


def test_a_block_that_is_not_a_block_is_refused() -> None:
    with pytest.raises(ConfigError, match="the retention section must be a mapping"):
        section({"retention": [1, 2]}, "retention", "archive")


def test_a_required_key_comes_back() -> None:
    assert require({"site": "US-TX-0417"}, "site", "config") == "US-TX-0417"


def test_a_missing_required_key_is_refused() -> None:
    with pytest.raises(ConfigError, match="config has no site"):
        require({}, "site", "config")


def test_a_required_key_can_keep_the_words_its_section_used() -> None:
    with pytest.raises(ConfigError, match="config has no site id"):
        require({}, "site", "config", "site id")


def test_the_site_config_still_refuses_an_unknown_section() -> None:
    with pytest.raises(ConfigError, match="unknown config key"):
        site_from_mapping({"site": "US-TX-0417", "nonsense": {}})


def test_the_site_config_suggests_a_section() -> None:
    with pytest.raises(ConfigError, match="did you mean archive"):
        site_from_mapping({"site": "US-TX-0417", "archiv": {}})


def test_a_config_with_no_site_is_refused() -> None:
    with pytest.raises(ConfigError, match="config has no site"):
        site_from_mapping({"spool": {}})


def test_a_section_written_empty_still_builds() -> None:
    built = site_from_mapping({"site": "US-TX-0417", "spool": None, "archive": None})
    assert built.site == "US-TX-0417"


def test_the_spool_section_still_refuses_an_unknown_key() -> None:
    with pytest.raises(ConfigError, match="unknown spool key"):
        spool_settings_from_mapping({"budgt": "1GiB"})


def test_the_ship_section_still_refuses_an_unknown_key() -> None:
    with pytest.raises(ConfigError, match="unknown ship key"):
        ship_settings_from_mapping({"endpiont": "https://c.invalid/v1"})


def test_the_archive_section_still_refuses_an_unknown_key() -> None:
    with pytest.raises(ConfigError, match="unknown archive key"):
        archive_settings_from_mapping({"pth": "/var/lib/x"})


def test_the_redact_section_still_refuses_an_unknown_key() -> None:
    with pytest.raises(ConfigError, match="unknown redact key"):
        redact_from_mapping({"enabld": True})


def test_the_multiline_rules_still_refuse_an_unknown_key() -> None:
    with pytest.raises(ConfigError, match="unknown multiline key"):
        rules_from_mapping({"pump-bridge": {"strt": "^ERROR"}})


def test_every_section_phrases_the_refusal_the_same_way() -> None:
    # The point of one helper: an engineer reading the error at eleven at night
    # sees the same sentence whichever section they typed into.
    messages = []
    for builder, bad in (
        (spool_settings_from_mapping, {"nonsense": 1}),
        (ship_settings_from_mapping, {"nonsense": 1}),
        (archive_settings_from_mapping, {"nonsense": 1}),
        (redact_from_mapping, {"nonsense": 1}),
    ):
        with pytest.raises(ConfigError) as caught:
            builder(bad)  # type: ignore[operator]
        messages.append(str(caught.value).split(" key")[0])
    assert messages == [
        "unknown spool",
        "unknown ship",
        "unknown archive",
        "unknown redact",
    ]


def test_the_priority_block_must_be_a_mapping() -> None:
    with pytest.raises(ConfigError, match="the priority section must be a mapping"):
        spool_settings_from_mapping({"priority": [1, 2]})


def test_the_floors_block_must_be_a_mapping() -> None:
    with pytest.raises(ConfigError, match="the floors section must be a mapping"):
        ship_settings_from_mapping({"floors": [1, 2]})


def test_the_rules_block_must_be_a_mapping() -> None:
    with pytest.raises(ConfigError, match="the rules section must be a mapping"):
        redact_from_mapping({"rules": [1, 2]})
