from __future__ import annotations

import gzip
from pathlib import Path

import pytest

from stanchion.errors import IngestError
from stanchion.ingest.backfill import Backfiller, read_whole, rotated_siblings
from stanchion.paths import parse_log_path
from tests.support.logfiles import SiteTree, cri_full, cri_line

TS = "2025-03-04T12:00:00.123456789Z"
SITE = "US-TX-0417"


@pytest.fixture
def live(tree: SiteTree) -> Path:
    return tree.write_cri("pump-bridge", [cri_full(TS, "the live file")])


def rotated(live: Path, suffix: str, text: str, compress: bool = False) -> Path:
    target = live.with_name(live.name + suffix)
    if compress:
        target.write_bytes(gzip.compress(text.encode("utf-8")))
    else:
        target.write_text(text, encoding="utf-8", newline="")
    return target


def backfiller(live: Path) -> Backfiller:
    return Backfiller(site=SITE, info=parse_log_path(live.as_posix()))


def test_a_live_file_with_no_rotations_has_no_sources(live: Path) -> None:
    assert rotated_siblings(live) == []


def test_a_rotated_file_is_found(live: Path) -> None:
    rotated(live, ".1", cri_full(TS, "older"))
    found = rotated_siblings(live)
    assert [item.rotation for item in found] == [1]


def test_the_live_file_is_never_a_source(live: Path) -> None:
    rotated(live, ".1", cri_full(TS, "older"))
    assert all(Path(item.path) != live for item in rotated_siblings(live))


def test_rotations_come_back_oldest_first(live: Path) -> None:
    rotated(live, ".1", cri_full(TS, "newer"))
    rotated(live, ".2", cri_full(TS, "older"))
    assert [item.rotation for item in rotated_siblings(live)] == [2, 1]


def test_a_compressed_rotation_is_found(live: Path) -> None:
    rotated(live, ".2.gz", cri_full(TS, "packed"), compress=True)
    found = rotated_siblings(live)
    assert found and found[0].compressed is True


def test_an_unrelated_file_beside_it_is_ignored(live: Path) -> None:
    (live.parent / "notes.txt").write_text("hello", encoding="utf-8")
    assert rotated_siblings(live) == []


def test_read_whole_reads_a_plain_file(live: Path) -> None:
    target = rotated(live, ".1", "hello")
    assert read_whole(target, compressed=False, max_bytes=1024) == b"hello"


def test_read_whole_decompresses(live: Path) -> None:
    target = rotated(live, ".1.gz", "hello", compress=True)
    assert read_whole(target, compressed=True, max_bytes=1024) == b"hello"


def test_read_whole_refuses_a_file_over_the_cap(live: Path) -> None:
    target = rotated(live, ".1", "x" * 100)
    with pytest.raises(IngestError, match="larger than the cap"):
        read_whole(target, compressed=False, max_bytes=10)


def test_read_whole_refuses_a_missing_file(live: Path) -> None:
    with pytest.raises(IngestError, match="unreadable"):
        read_whole(live.with_name("absent.log"), compressed=False, max_bytes=1024)


def test_read_whole_refuses_something_that_is_not_gzip(live: Path) -> None:
    target = rotated(live, ".1.gz", "not actually gzip")
    with pytest.raises(IngestError, match="not gzip"):
        read_whole(target, compressed=True, max_bytes=1024)


def test_records_come_out_of_a_rotated_file(live: Path) -> None:
    rotated(live, ".1", cri_full(TS, "one") + cri_full(TS, "two"))
    found = list(backfiller(live).records())
    assert [item.message for item in found] == ["one", "two"]


def test_records_come_out_of_a_compressed_file(live: Path) -> None:
    rotated(live, ".1.gz", cri_full(TS, "packed one"), compress=True)
    found = list(backfiller(live).records())
    assert [item.message for item in found] == ["packed one"]


def test_the_records_carry_the_site_and_unit(live: Path) -> None:
    rotated(live, ".1", cri_full(TS, "one"))
    found = list(backfiller(live).records())
    assert found[0].site == SITE
    assert found[0].unit == "pump-bridge"


def test_older_files_come_first(live: Path) -> None:
    rotated(live, ".1", cri_full(TS, "newer"))
    rotated(live, ".2", cri_full(TS, "older"))
    found = list(backfiller(live).records())
    assert [item.message for item in found] == ["older", "newer"]


def test_split_chunks_inside_one_file_are_joined(live: Path) -> None:
    text = cri_line(TS, "stdout", "P", "first ") + cri_line(TS, "stdout", "F", "second")
    rotated(live, ".1", text)
    found = list(backfiller(live).records())
    assert [item.message for item in found] == ["first second"]


def test_a_line_left_open_at_the_end_is_flushed(live: Path) -> None:
    rotated(live, ".1", cri_line(TS, "stdout", "P", "never closed"))
    found = list(backfiller(live).records())
    assert [item.message for item in found] == ["never closed"]
    assert found[0].labels["truncated"] == "true"


def test_a_chunk_is_not_joined_across_a_rotation(live: Path) -> None:
    rotated(live, ".2", cri_line(TS, "stdout", "P", "half from the older file"))
    rotated(live, ".1", cri_full(TS, "a whole line from the newer one"))
    found = list(backfiller(live).records())
    assert len(found) == 2
    assert found[0].message == "half from the older file"


def test_the_report_counts_files_and_records(live: Path) -> None:
    rotated(live, ".1", cri_full(TS, "one"))
    rotated(live, ".2", cri_full(TS, "two") + cri_full(TS, "three"))
    built = backfiller(live)
    list(built.records())
    assert built.report.files == 2
    assert built.report.records == 3
    assert built.report.bytes_read > 0


def test_the_report_reads_as_one_line(live: Path) -> None:
    rotated(live, ".1", cri_full(TS, "one"))
    built = backfiller(live)
    list(built.records())
    assert str(built.report) == "backfilled 1 records from 1 files"


def test_an_unreadable_file_is_named_and_skipped(live: Path) -> None:
    rotated(live, ".1.gz", "not gzip at all")
    rotated(live, ".2", cri_full(TS, "still readable"))
    built = backfiller(live)
    found = list(built.records())
    assert [item.message for item in found] == ["still readable"]
    assert built.report.failed


def test_a_bad_line_inside_a_file_is_skipped(live: Path) -> None:
    rotated(live, ".1", cri_full(TS, "one") + "garbage\n" + cri_full(TS, "three"))
    built = backfiller(live)
    found = list(built.records())
    assert [item.message for item in found] == ["one", "three"]
    assert built.report.skipped == 1


def test_an_empty_rotated_file_yields_nothing_and_is_not_a_failure(live: Path) -> None:
    # logrotate leaves an empty file behind routinely. There is nothing in it
    # and nothing wrong with it.
    rotated(live, ".1", "")
    built = backfiller(live)
    assert list(built.records()) == []
    assert built.report.failed == []
    assert built.report.files == 1
