"""Priority classes: the one setting that decides three other things.

A class is assigned per unit and it then governs, all at once:

* **ship order.** Payment traffic goes up the metered link before the price sign
  driver's heartbeat does.
* **eviction order.** When the disk budget is reached, the lowest class is
  deleted first, and the highest may be protected from eviction entirely.
* **retention floor.** Payment records have a dispute window; a heartbeat does
  not.

Keeping those three as one setting is deliberate. They were separate once and
sites ended up with a class shipped first and evicted first, which is the worst
of both: the records that matter most were the ones most likely to be gone
before anyone looked.

Ordering is by weight, high first. Two classes may not share a weight, because
"which of these two goes first" would then depend on dictionary order, and the
answer would change when somebody reordered the config file.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from stanchion.errors import ConfigError
from stanchion.identity import parse_unit
from stanchion.spool.segment import check_class

DEFAULT_CLASS = "standard"


@dataclass(frozen=True, slots=True)
class PriorityClass:
    """One class, and everything it decides."""

    name: str
    weight: int
    protected: bool = False

    def __post_init__(self) -> None:
        check_class(self.name)
        if self.weight < 0:
            raise ConfigError("a class weight cannot be negative", detail=self.name)


BUILTIN_CLASSES: tuple[PriorityClass, ...] = (
    PriorityClass(name="payment", weight=100, protected=True),
    PriorityClass(name="standard", weight=50),
    PriorityClass(name="chatter", weight=10),
)


@dataclass(slots=True)
class PriorityTable:
    """The classes on this site and which unit is in which."""

    classes: tuple[PriorityClass, ...] = BUILTIN_CLASSES
    assignments: dict[str, str] = field(default_factory=dict)
    default: str = DEFAULT_CLASS

    def __post_init__(self) -> None:
        names = [item.name for item in self.classes]
        if len(set(names)) != len(names):
            raise ConfigError("two priority classes share a name")
        weights = [item.weight for item in self.classes]
        if len(set(weights)) != len(weights):
            raise ConfigError("two priority classes share a weight")
        if self.default not in names:
            raise ConfigError("the default class is not defined", detail=self.default)
        cleaned: dict[str, str] = {}
        for unit, klass in self.assignments.items():
            if klass not in names:
                raise ConfigError("a unit is assigned to an unknown class", detail=klass)
            cleaned[parse_unit(unit)] = klass
        self.assignments = cleaned

    def by_name(self, name: str) -> PriorityClass:
        for item in self.classes:
            if item.name == name:
                return item
        raise ConfigError("unknown priority class", detail=name)

    def class_for(self, unit: str) -> PriorityClass:
        """Which class a unit's records belong to."""
        return self.by_name(self.assignments.get(unit, self.default))

    def ship_order(self) -> tuple[str, ...]:
        """Class names, highest weight first."""
        ordered = sorted(self.classes, key=lambda item: (-item.weight, item.name))
        return tuple(item.name for item in ordered)

    def evict_order(self) -> tuple[str, ...]:
        """Class names in the order they may be deleted, lowest weight first.

        Protected classes are not in this list at all. A protected class is not
        "evicted last", it is never evicted, and returning it at the end of the
        list would let a caller that ran off the end delete it anyway.
        """
        ordered = sorted(
            (item for item in self.classes if not item.protected),
            key=lambda item: (item.weight, item.name),
        )
        return tuple(item.name for item in ordered)

    @property
    def protected_classes(self) -> frozenset[str]:
        return frozenset(item.name for item in self.classes if item.protected)


def table_from_mapping(raw: dict[str, Any]) -> PriorityTable:
    """Build the table from the config section."""
    unknown = set(raw) - {"classes", "units", "default"}
    if unknown:
        raise ConfigError("unknown priority key", detail=", ".join(sorted(unknown)))
    classes_raw = raw.get("classes")
    if classes_raw is None:
        classes = BUILTIN_CLASSES
    else:
        if not isinstance(classes_raw, dict):
            raise ConfigError("priority classes must be a mapping")
        classes = tuple(
            PriorityClass(
                name=str(name),
                weight=int(body.get("weight", 0)) if isinstance(body, dict) else int(body),
                protected=bool(body.get("protected", False)) if isinstance(body, dict) else False,
            )
            for name, body in classes_raw.items()
        )
    units_raw = raw.get("units", {})
    if not isinstance(units_raw, dict):
        raise ConfigError("priority unit assignments must be a mapping")
    return PriorityTable(
        classes=classes,
        assignments={str(unit): str(klass) for unit, klass in units_raw.items()},
        default=str(raw.get("default", DEFAULT_CLASS)),
    )
