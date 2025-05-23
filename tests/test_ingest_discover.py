from __future__ import annotations

from pathlib import Path

from stanchion.ingest.discover import SkipReason, classify, deduplicate, discover, walk
from stanchion.paths import Layout, parse_log_path
from tests.support.logfiles import SiteTree, cri_full

TS = "2025-03-04T11:30:00.123456789Z"
CID = "3f" * 32


def seed(tree: SiteTree, unit: str, attempt: int = 0) -> Path:
    return tree.write_cri(unit, [cri_full(TS, f"{unit} is up")], attempt=attempt)


def test_walk_of_an_absent_root_is_empty(tmp_path: Path) -> None:
    assert list(walk(tmp_path / "nope")) == []


def test_walk_finds_files_in_a_stable_order(tree: SiteTree) -> None:
    seed(tree, "pump-bridge")
    seed(tree, "tank-gauge")
    first = list(walk(tree.root))
    assert first == list(walk(tree.root))
    assert len(first) == 2


def test_walk_ignores_directories(tree: SiteTree) -> None:
    seed(tree, "pump-bridge")
    assert all(path.is_file() for path in walk(tree.root))


def test_discover_follows_a_live_file(tree: SiteTree) -> None:
    seed(tree, "pump-bridge")
    found = discover(tree.root)
    assert [info.unit for info in found.follow] == ["pump-bridge"]
    assert found.skipped == []


def test_discover_sorts_units(tree: SiteTree) -> None:
    seed(tree, "tank-gauge")
    seed(tree, "pump-bridge")
    assert discover(tree.root).units == ("pump-bridge", "tank-gauge")


def test_a_rotated_file_is_skipped(tree: SiteTree) -> None:
    path = seed(tree, "pump-bridge")
    tree.rotate(path)
    found = discover(tree.root)
    assert found.reasons()[SkipReason.ROTATED] == 1
    assert [info.unit for info in found.follow] == ["pump-bridge"]


def test_a_compressed_file_is_skipped(tree: SiteTree) -> None:
    path = seed(tree, "pump-bridge")
    path.with_name(path.name + ".1.gz").write_bytes(b"packed")
    found = discover(tree.root)
    assert found.reasons()[SkipReason.COMPRESSED] == 1


def test_an_unrelated_file_is_skipped_as_unparseable(tree: SiteTree) -> None:
    seed(tree, "pump-bridge")
    (tree.root / "messages").write_text("nothing to do with us", encoding="utf-8")
    found = discover(tree.root)
    assert found.reasons()[SkipReason.UNPARSEABLE] == 1


def test_classify_returns_candidates_and_skips(tree: SiteTree) -> None:
    seed(tree, "pump-bridge")
    (tree.root / "messages").write_text("x", encoding="utf-8")
    candidates, skipped = classify(list(walk(tree.root)))
    assert len(candidates) == 1
    assert len(skipped) == 1


def test_two_layouts_of_one_unit_collapse_to_the_preferred_one() -> None:
    cri = parse_log_path(
        "/var/log/pods/forecourt_pump-bridge-0_1f2e3d4c-aaaa-bbbb-cccc-1234567890ab"
        "/pump-bridge/0.log"
    )
    flat = parse_log_path(f"/var/log/containers/pump-bridge-0_forecourt_pump-bridge-{CID}.log")
    kept, losers = deduplicate([flat, cri])
    assert [info.layout for info in kept] == [Layout.CRI]
    assert losers[0].reason is SkipReason.DUPLICATE


def test_the_preference_holds_whichever_order_they_arrive_in() -> None:
    cri = parse_log_path(
        "/var/log/pods/forecourt_pump-bridge-0_1f2e3d4c-aaaa-bbbb-cccc-1234567890ab"
        "/pump-bridge/0.log"
    )
    flat = parse_log_path(f"/var/log/containers/pump-bridge-0_forecourt_pump-bridge-{CID}.log")
    assert deduplicate([cri, flat])[0][0].layout is Layout.CRI
    assert deduplicate([flat, cri])[0][0].layout is Layout.CRI


def test_the_later_restart_attempt_wins_inside_one_layout(tree: SiteTree) -> None:
    seed(tree, "pump-bridge", attempt=0)
    seed(tree, "pump-bridge", attempt=3)
    found = discover(tree.root)
    assert len(found.follow) == 1
    assert found.follow[0].attempt == 3
    assert found.reasons()[SkipReason.DUPLICATE] == 1


def test_two_different_units_are_both_kept(tree: SiteTree) -> None:
    seed(tree, "pump-bridge")
    seed(tree, "tank-gauge")
    assert len(discover(tree.root).follow) == 2


def test_the_unit_filter_selects(tree: SiteTree) -> None:
    seed(tree, "pump-bridge")
    seed(tree, "tank-gauge")
    found = discover(tree.root, units=("pump-bridge",))
    assert [info.unit for info in found.follow] == ["pump-bridge"]
    assert found.reasons()[SkipReason.NOT_SELECTED] == 1


def test_an_empty_filter_selects_everything(tree: SiteTree) -> None:
    seed(tree, "pump-bridge")
    seed(tree, "tank-gauge")
    assert len(discover(tree.root, units=()).follow) == 2


def test_a_filter_naming_an_absent_unit_follows_nothing(tree: SiteTree) -> None:
    seed(tree, "pump-bridge")
    assert discover(tree.root, units=("loyalty-sync",)).follow == []


def test_reasons_counts_each_kind(tree: SiteTree) -> None:
    path = seed(tree, "pump-bridge")
    tree.rotate(path)
    (tree.root / "messages").write_text("x", encoding="utf-8")
    counts = discover(tree.root).reasons()
    assert counts[SkipReason.ROTATED] == 1
    assert counts[SkipReason.UNPARSEABLE] == 1


def test_reasons_of_a_clean_walk_is_empty(tree: SiteTree) -> None:
    seed(tree, "pump-bridge")
    assert discover(tree.root).reasons() == {}


def test_discovery_of_an_empty_root_finds_nothing(tmp_path: Path) -> None:
    found = discover(tmp_path)
    assert found.follow == []
    assert found.units == ()
