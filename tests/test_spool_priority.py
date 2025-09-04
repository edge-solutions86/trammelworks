from __future__ import annotations

from typing import Any

import pytest

from stanchion.errors import ConfigError
from stanchion.spool.priority import (
    BUILTIN_CLASSES,
    PriorityClass,
    PriorityTable,
    table_from_mapping,
)


def test_the_builtin_classes_cover_the_three_cases() -> None:
    names = {item.name for item in BUILTIN_CLASSES}
    assert names == {"payment", "standard", "chatter"}


def test_payment_outranks_everything() -> None:
    assert PriorityTable().ship_order()[0] == "payment"


def test_payment_is_protected_by_default() -> None:
    assert PriorityTable().protected_classes == frozenset({"payment"})


def test_an_unassigned_unit_lands_in_the_default() -> None:
    assert PriorityTable().class_for("pump-bridge").name == "standard"


def test_an_assigned_unit_lands_in_its_class() -> None:
    table = PriorityTable(assignments={"payment-kernel": "payment"})
    assert table.class_for("payment-kernel").name == "payment"


def test_assignments_are_normalized() -> None:
    table = PriorityTable(assignments={"Payment-Kernel": "payment"})
    assert table.class_for("payment-kernel").name == "payment"


def test_an_assignment_to_an_unknown_class_is_refused() -> None:
    with pytest.raises(ConfigError, match="unknown class"):
        PriorityTable(assignments={"payment-kernel": "urgent"})


def test_a_default_that_does_not_exist_is_refused() -> None:
    with pytest.raises(ConfigError, match="default class is not defined"):
        PriorityTable(default="urgent")


def test_two_classes_may_not_share_a_name() -> None:
    twice = (PriorityClass(name="a", weight=1), PriorityClass(name="a", weight=2))
    with pytest.raises(ConfigError, match="share a name"):
        PriorityTable(classes=twice, default="a")


def test_two_classes_may_not_share_a_weight() -> None:
    twice = (PriorityClass(name="a", weight=1), PriorityClass(name="b", weight=1))
    with pytest.raises(ConfigError, match="share a weight"):
        PriorityTable(classes=twice, default="a")


def test_a_negative_weight_is_refused() -> None:
    with pytest.raises(ConfigError, match="cannot be negative"):
        PriorityClass(name="a", weight=-1)


def test_a_malformed_class_name_is_refused() -> None:
    with pytest.raises(Exception, match="malformed priority class"):
        PriorityClass(name="Payment", weight=1)


def test_ship_order_runs_highest_weight_first() -> None:
    assert PriorityTable().ship_order() == ("payment", "standard", "chatter")


def test_evict_order_runs_lowest_weight_first() -> None:
    assert PriorityTable().evict_order() == ("chatter", "standard")


def test_a_protected_class_is_absent_from_the_evict_order() -> None:
    assert "payment" not in PriorityTable().evict_order()


def test_the_two_orders_are_opposites_over_what_they_share() -> None:
    table = PriorityTable()
    evictable = [name for name in table.ship_order() if name in table.evict_order()]
    assert list(reversed(evictable)) == list(table.evict_order())


def test_by_name_finds_a_class() -> None:
    assert PriorityTable().by_name("chatter").weight == 10


def test_by_name_refuses_an_unknown_class() -> None:
    with pytest.raises(ConfigError, match="unknown priority class"):
        PriorityTable().by_name("urgent")


def test_the_mapping_builder_keeps_the_builtins_when_none_are_given() -> None:
    assert table_from_mapping({}).classes == BUILTIN_CLASSES


def test_the_mapping_builder_reads_assignments() -> None:
    table = table_from_mapping({"units": {"payment-kernel": "payment"}})
    assert table.class_for("payment-kernel").name == "payment"


def test_the_mapping_builder_reads_custom_classes() -> None:
    raw: dict[str, Any] = {
        "classes": {
            "urgent": {"weight": 200, "protected": True},
            "normal": {"weight": 50},
        },
        "default": "normal",
    }
    table = table_from_mapping(raw)
    assert table.ship_order() == ("urgent", "normal")
    assert table.protected_classes == frozenset({"urgent"})


def test_a_class_may_be_written_as_a_bare_weight() -> None:
    table = table_from_mapping({"classes": {"only": 5}, "default": "only"})
    assert table.by_name("only").weight == 5


def test_the_mapping_builder_rejects_an_unknown_key() -> None:
    with pytest.raises(ConfigError, match="unknown priority key"):
        table_from_mapping({"clases": {}})


def test_the_mapping_builder_needs_classes_to_be_a_mapping() -> None:
    with pytest.raises(ConfigError, match="classes must be a mapping"):
        table_from_mapping({"classes": ["payment"]})


def test_the_mapping_builder_needs_units_to_be_a_mapping() -> None:
    with pytest.raises(ConfigError, match="assignments must be a mapping"):
        table_from_mapping({"units": ["payment-kernel"]})


def test_one_setting_decides_ship_and_evict_together() -> None:
    table = PriorityTable(assignments={"payment-kernel": "payment", "price-sign": "chatter"})
    payment = table.class_for("payment-kernel")
    sign = table.class_for("price-sign")
    assert payment.weight > sign.weight
    assert table.ship_order().index("payment") < table.ship_order().index("chatter")
    assert table.evict_order().index("chatter") == 0
