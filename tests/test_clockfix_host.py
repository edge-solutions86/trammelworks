from __future__ import annotations

from pathlib import Path

from stanchion.clockfix.host import (
    FALLBACK_FILENAME,
    current_boot_identity,
    read_fallback_boot_id,
    read_kernel_boot_id,
    write_fallback_boot_id,
)
from stanchion.clockfix.session import parse_boot_id

KERNEL_FORM = "4c1f2e3d-4c1f-2e3d-4c1f-2e3d4c1f2e3d"
FLAT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d"


def proc_file(tmp_path: Path, text: str) -> Path:
    target = tmp_path / "boot_id"
    target.write_text(text, encoding="ascii")
    return target


def test_the_kernel_form_with_dashes_is_read(tmp_path: Path) -> None:
    assert read_kernel_boot_id(proc_file(tmp_path, KERNEL_FORM + "\n")) == FLAT


def test_a_flat_kernel_value_is_read(tmp_path: Path) -> None:
    assert read_kernel_boot_id(proc_file(tmp_path, FLAT)) == FLAT


def test_a_missing_proc_file_gives_none(tmp_path: Path) -> None:
    assert read_kernel_boot_id(tmp_path / "absent") is None


def test_a_directory_in_place_of_the_proc_file_gives_none(tmp_path: Path) -> None:
    assert read_kernel_boot_id(tmp_path) is None


def test_a_junk_proc_value_gives_none(tmp_path: Path) -> None:
    assert read_kernel_boot_id(proc_file(tmp_path, "not a boot id")) is None


def test_an_empty_proc_file_gives_none(tmp_path: Path) -> None:
    assert read_kernel_boot_id(proc_file(tmp_path, "")) is None


def test_write_fallback_mints_a_valid_id(tmp_path: Path) -> None:
    minted = write_fallback_boot_id(tmp_path / "state")
    assert parse_boot_id(minted) == minted


def test_write_fallback_creates_the_directory(tmp_path: Path) -> None:
    write_fallback_boot_id(tmp_path / "deep" / "state")
    assert (tmp_path / "deep" / "state" / FALLBACK_FILENAME).exists()


def test_write_fallback_gives_a_different_id_each_time(tmp_path: Path) -> None:
    first = write_fallback_boot_id(tmp_path)
    second = write_fallback_boot_id(tmp_path)
    assert first != second


def test_the_fallback_is_read_back(tmp_path: Path) -> None:
    minted = write_fallback_boot_id(tmp_path)
    assert read_fallback_boot_id(tmp_path) == minted


def test_reading_an_absent_fallback_gives_none(tmp_path: Path) -> None:
    assert read_fallback_boot_id(tmp_path) is None


def test_reading_a_corrupt_fallback_gives_none(tmp_path: Path) -> None:
    (tmp_path / FALLBACK_FILENAME).write_text("junk", encoding="ascii")
    assert read_fallback_boot_id(tmp_path) is None


def test_the_kernel_wins_when_it_is_there(tmp_path: Path) -> None:
    found = current_boot_identity(tmp_path / "state", proc_file(tmp_path, KERNEL_FORM))
    assert found.boot_id == FLAT
    assert found.source == "kernel"
    assert found.from_kernel is True


def test_the_fallback_is_used_when_there_is_no_proc(tmp_path: Path) -> None:
    found = current_boot_identity(tmp_path / "state", tmp_path / "absent")
    assert found.source == "fallback"
    assert found.from_kernel is False
    assert parse_boot_id(found.boot_id) == found.boot_id


def test_a_second_run_mints_a_new_fallback_by_default(tmp_path: Path) -> None:
    first = current_boot_identity(tmp_path / "state", tmp_path / "absent")
    second = current_boot_identity(tmp_path / "state", tmp_path / "absent")
    assert first.boot_id != second.boot_id


def test_reuse_brings_back_the_stored_fallback(tmp_path: Path) -> None:
    first = current_boot_identity(tmp_path / "state", tmp_path / "absent")
    second = current_boot_identity(tmp_path / "state", tmp_path / "absent", reuse_fallback=True)
    assert second.boot_id == first.boot_id
    assert second.source == "fallback-reused"


def test_reuse_still_mints_when_there_is_nothing_stored(tmp_path: Path) -> None:
    found = current_boot_identity(tmp_path / "state", tmp_path / "absent", reuse_fallback=True)
    assert found.source == "fallback"


def test_reuse_is_ignored_when_the_kernel_answers(tmp_path: Path) -> None:
    write_fallback_boot_id(tmp_path / "state")
    found = current_boot_identity(
        tmp_path / "state", proc_file(tmp_path, KERNEL_FORM), reuse_fallback=True
    )
    assert found.boot_id == FLAT
