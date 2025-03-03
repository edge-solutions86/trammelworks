from __future__ import annotations

import json

from stanchion.paths import Layout, parse_log_path
from tests.support.logfiles import (
    SiteTree,
    cri_full,
    cri_line,
    cri_split,
    docker_line,
    journal_export,
)

TS = "2025-03-04T11:30:00.123456789Z"
CID = "3f" * 32


def test_cri_line_has_four_space_separated_parts() -> None:
    line = cri_line(TS, "stdout", "F", "auth approved")
    assert line == f"{TS} stdout F auth approved\n"
    assert line.count(" ") >= 3


def test_cri_full_defaults_to_stdout() -> None:
    assert " stdout F " in cri_full(TS, "hello")


def test_cri_split_produces_a_partial_then_a_final() -> None:
    text = cri_split(TS, "abcdef", at=4)
    first, second = text.splitlines()
    assert first.endswith(" P abcd")
    assert second.endswith(" F ef")


def test_cri_split_keeps_the_whole_message_between_the_two_chunks() -> None:
    text = cri_split(TS, "the quick brown fox", at=9)
    joined = "".join(line.split(" ", 3)[3] for line in text.splitlines())
    assert joined == "the quick brown fox"


def test_docker_line_is_json_with_the_newline_kept() -> None:
    payload = json.loads(docker_line(TS, "auth approved"))
    assert payload["log"] == "auth approved\n"
    assert payload["stream"] == "stdout"
    assert payload["time"] == TS


def test_docker_line_can_be_stderr() -> None:
    assert json.loads(docker_line(TS, "boom", stream="stderr"))["stream"] == "stderr"


def test_journal_export_ends_with_a_blank_line() -> None:
    block = journal_export({"MESSAGE": "auth approved", "_PID": "412"})
    assert block.endswith("\n\n")
    assert "MESSAGE=auth approved" in block


def test_site_tree_writes_a_cri_path_the_parser_recognises(tree: SiteTree) -> None:
    path = tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    info = parse_log_path(path.as_posix())
    assert info.layout is Layout.CRI
    assert info.unit == "pump-bridge"
    assert info.attempt == 0


def test_site_tree_records_the_size_it_wrote(tree: SiteTree) -> None:
    path = tree.write_cri("pump-bridge", [cri_full(TS, "up")])
    assert tree.written[str(path)] == path.stat().st_size


def test_site_tree_appends_rather_than_replacing(tree: SiteTree) -> None:
    tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    path = tree.write_cri("pump-bridge", [cri_full(TS, "two")])
    assert len(path.read_text(encoding="utf-8").splitlines()) == 2


def test_site_tree_separates_attempts(tree: SiteTree) -> None:
    first = tree.write_cri("pump-bridge", [cri_full(TS, "one")], attempt=0)
    second = tree.write_cri("pump-bridge", [cri_full(TS, "two")], attempt=1)
    assert first != second
    assert parse_log_path(second.as_posix()).attempt == 1


def test_site_tree_flat_path_parses(tree: SiteTree) -> None:
    path = tree.flat_path("tank-gauge", CID)
    info = parse_log_path(path.as_posix())
    assert info.layout is Layout.FLAT
    assert info.container_id == CID


def test_site_tree_docker_path_parses(tree: SiteTree) -> None:
    info = parse_log_path(tree.docker_path(CID).as_posix())
    assert info.layout is Layout.DOCKER


def test_rotate_moves_the_file_and_leaves_an_empty_one(tree: SiteTree) -> None:
    path = tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    rotated = tree.rotate(path)
    assert rotated.name.endswith(".log.1")
    assert rotated.read_text(encoding="utf-8").strip() != ""
    assert path.exists()
    assert path.stat().st_size == 0


def test_rotate_twice_replaces_the_previous_rotation(tree: SiteTree) -> None:
    path = tree.write_cri("pump-bridge", [cri_full(TS, "one")])
    tree.rotate(path)
    tree.write_cri("pump-bridge", [cri_full(TS, "two")])
    rotated = tree.rotate(path)
    assert "two" in rotated.read_text(encoding="utf-8")


def test_truncate_cuts_the_tail_off(tree: SiteTree) -> None:
    path = tree.write_cri("pump-bridge", [cri_full(TS, "a long enough message")])
    tree.truncate(path, 20)
    assert path.stat().st_size == 20
    assert not path.read_text(encoding="utf-8").endswith("\n")
