from __future__ import annotations

import os
from pathlib import Path

import pytest

from stanchion.errors import IngestError
from stanchion.ingest.confine import (
    Confinement,
    Refusal,
    Verdict,
    check,
    resolve_root,
    within,
)
from stanchion.ingest.discover import SkipReason, discover
from tests.support.logfiles import SiteTree, cri_full

TS = "2025-03-04T12:00:00.123456789Z"


def linkable(target: Path, link: Path) -> bool:
    """Make a symlink, or say this box will not let us.

    Windows refuses without developer mode or an elevated shell, and a test
    that quietly passes there is worse than one that says it was skipped.
    """
    try:
        link.symlink_to(target)
    except (OSError, NotImplementedError):
        return False
    return True


@pytest.fixture
def root(tmp_path: Path) -> Path:
    made = tmp_path / "containers"
    made.mkdir(parents=True)
    return made


def test_a_root_resolves(root: Path) -> None:
    assert resolve_root(root) == root.resolve()


def test_a_root_that_is_not_there_still_resolves(tmp_path: Path) -> None:
    assert resolve_root(tmp_path / "absent").name == "absent"


def test_a_path_under_the_root_is_within(root: Path) -> None:
    assert within(root, root / "a" / "b.log")


def test_the_root_itself_is_within(root: Path) -> None:
    assert within(root, root)


def test_a_path_beside_the_root_is_not_within(root: Path, tmp_path: Path) -> None:
    assert not within(root, tmp_path / "elsewhere" / "b.log")


def test_a_path_whose_name_starts_the_same_is_not_within(root: Path) -> None:
    # containers-old is not inside containers, however it sorts.
    assert not within(root, root.with_name(root.name + "-old") / "b.log")


def test_an_ordinary_file_is_allowed(root: Path) -> None:
    target = root / "0.log"
    target.write_text("hello", encoding="utf-8")
    verdict = check(target, root.resolve())
    assert verdict.ok
    assert bool(verdict) is True


def test_a_missing_file_is_refused(root: Path) -> None:
    verdict = check(root / "absent.log", root.resolve())
    assert verdict.refusal is Refusal.UNREADABLE


def test_a_directory_is_not_a_file(root: Path) -> None:
    (root / "inner").mkdir()
    assert check(root / "inner", root.resolve()).refusal is Refusal.NOT_A_FILE


def test_a_verdict_says_what_it_decided(root: Path) -> None:
    target = root / "0.log"
    target.write_text("hi", encoding="utf-8")
    assert "ok" in str(check(target, root.resolve()))
    assert "refused" in str(Verdict(path=target, refusal=Refusal.OUTSIDE))


def test_a_link_inside_the_root_is_allowed(root: Path) -> None:
    real = root / "docker" / "0.log"
    real.parent.mkdir(parents=True)
    real.write_text("hello", encoding="utf-8")
    link = root / "cri.log"
    if not linkable(real, link):
        pytest.skip("this platform will not make symlinks")
    assert check(link, root.resolve()).ok


def test_a_link_out_of_the_root_is_refused(root: Path, tmp_path: Path) -> None:
    outside = tmp_path / "secrets"
    outside.write_text("not a log", encoding="utf-8")
    link = root / "0.log"
    if not linkable(outside, link):
        pytest.skip("this platform will not make symlinks")
    verdict = check(link, root.resolve())
    assert verdict.refusal is Refusal.OUTSIDE
    assert verdict.resolved == outside.resolve()


def test_a_link_to_a_directory_out_of_the_root_is_refused(root: Path, tmp_path: Path) -> None:
    outside = tmp_path / "elsewhere"
    (outside / "deep").mkdir(parents=True)
    (outside / "deep" / "0.log").write_text("x", encoding="utf-8")
    link = root / "moved"
    if not linkable(outside, link):
        pytest.skip("this platform will not make symlinks")
    assert check(link / "deep" / "0.log", root.resolve()).refusal is Refusal.OUTSIDE


def test_a_broken_link_is_refused(root: Path) -> None:
    link = root / "0.log"
    if not linkable(root / "gone.log", link):
        pytest.skip("this platform will not make symlinks")
    assert check(link, root.resolve()).refusal is Refusal.UNREADABLE


def test_a_fifo_is_refused(root: Path) -> None:
    target = root / "pipe.log"
    if not hasattr(os, "mkfifo"):
        pytest.skip("no fifos on this platform")
    os.mkfifo(target)  # type: ignore[attr-defined]
    assert check(target, root.resolve()).refusal is Refusal.NOT_A_FILE


def test_a_confinement_resolves_the_root_once(root: Path) -> None:
    fence = Confinement(root=root)
    first = fence.prepare()
    assert fence.prepare() is first


def test_a_confinement_splits_a_walk(root: Path, tmp_path: Path) -> None:
    good = root / "0.log"
    good.write_text("x", encoding="utf-8")
    outside = tmp_path / "secrets"
    outside.write_text("x", encoding="utf-8")
    bad = root / "1.log"
    if not linkable(outside, bad):
        pytest.skip("this platform will not make symlinks")
    kept, refused = Confinement(root=root).keep([good, bad])
    assert kept == [good]
    assert [item.refusal for item in refused] == [Refusal.OUTSIDE]


def test_a_confinement_with_nothing_to_check_is_empty(root: Path) -> None:
    assert Confinement(root=root).keep([]) == ([], [])


def test_an_unresolvable_root_is_refused() -> None:
    class Awkward:
        def resolve(self, strict: bool = False) -> Path:
            raise OSError("no")

    with pytest.raises(IngestError, match="cannot be resolved"):
        resolve_root(Awkward())  # type: ignore[arg-type]


def test_discovery_still_finds_ordinary_files(tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    found = discover(tree.root)
    assert [info.unit for info in found.follow] == ["pump-bridge"]
    assert found.refused == []


def test_discovery_refuses_a_link_out_of_the_root(tree: SiteTree, tmp_path: Path) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    outside = tmp_path / "elsewhere.log"
    outside.write_text("not ours", encoding="utf-8")
    link = tree.root / "tank-gauge" / "0.log"
    link.parent.mkdir(parents=True, exist_ok=True)
    if not linkable(outside, link):
        pytest.skip("this platform will not make symlinks")
    found = discover(tree.root)
    assert [info.unit for info in found.follow] == ["pump-bridge"]
    assert found.reasons().get(SkipReason.OUTSIDE) == 1


def test_the_refusal_is_reported_not_raised(tree: SiteTree, tmp_path: Path) -> None:
    outside = tmp_path / "elsewhere.log"
    outside.write_text("not ours", encoding="utf-8")
    link = tree.root / "tank-gauge" / "0.log"
    link.parent.mkdir(parents=True, exist_ok=True)
    if not linkable(outside, link):
        pytest.skip("this platform will not make symlinks")
    found = discover(tree.root)
    assert len(found.refused) == 1
    assert found.refused[0].refusal is Refusal.OUTSIDE
