from __future__ import annotations

import json
from pathlib import Path

import pytest

from stanchion.errors import StanchionError
from stanchion.jsonstore import JsonStore, read_json, write_atomic


def test_a_mapping_is_written_and_read_back(tmp_path: Path) -> None:
    target = tmp_path / "state" / "thing.json"
    write_atomic(target, {"a": 1})
    assert json.loads(target.read_text(encoding="utf-8")) == {"a": 1}


def test_the_directory_is_created(tmp_path: Path) -> None:
    write_atomic(tmp_path / "deep" / "state" / "thing.json", {})
    assert (tmp_path / "deep" / "state").is_dir()


def test_no_temporary_file_is_left_behind(tmp_path: Path) -> None:
    target = tmp_path / "state" / "thing.json"
    write_atomic(target, {"a": 1})
    assert [item.name for item in target.parent.iterdir()] == ["thing.json"]


def test_the_temporary_file_shares_the_directory(tmp_path: Path) -> None:
    # os.replace is only atomic within one filesystem, so the temporary file
    # must not land in the system temp directory.
    seen: list[Path] = []
    target = tmp_path / "state" / "thing.json"
    target.parent.mkdir(parents=True)

    class Watcher(dict):  # type: ignore[type-arg]
        def items(self):  # type: ignore[no-untyped-def]
            seen.extend(target.parent.iterdir())
            return super().items()

    write_atomic(target, Watcher(a=1))
    assert all(item.parent == target.parent for item in seen)


def test_writing_twice_replaces(tmp_path: Path) -> None:
    target = tmp_path / "thing.json"
    write_atomic(target, {"a": 1})
    write_atomic(target, {"a": 2})
    assert json.loads(target.read_text(encoding="utf-8")) == {"a": 2}


def test_the_written_form_is_sorted(tmp_path: Path) -> None:
    target = tmp_path / "thing.json"
    write_atomic(target, {"z": 1, "a": 2})
    body = target.read_text(encoding="utf-8")
    assert body.index('"a"') < body.index('"z"')


def test_an_indent_can_be_asked_for(tmp_path: Path) -> None:
    target = tmp_path / "thing.json"
    write_atomic(target, {"a": 1, "b": 2}, indent=1)
    assert len(target.read_text(encoding="utf-8").splitlines()) > 1


def test_a_failure_leaves_no_temporary_file(tmp_path: Path) -> None:
    target = tmp_path / "thing.json"
    with pytest.raises(TypeError):
        write_atomic(target, {"a": object()})
    assert list(tmp_path.iterdir()) == []


def test_reading_a_missing_file_is_reported(tmp_path: Path) -> None:
    found = read_json(tmp_path / "absent.json")
    assert found.missing is True
    assert found.usable is False


def test_reading_a_directory_is_an_error(tmp_path: Path) -> None:
    with pytest.raises(StanchionError, match="state file unreadable"):
        read_json(tmp_path)


def test_reading_broken_json_is_corrupt(tmp_path: Path) -> None:
    target = tmp_path / "thing.json"
    target.write_text("{broken", encoding="utf-8")
    found = read_json(target)
    assert found.corrupt is True
    assert found.usable is False


def test_reading_a_json_array_is_corrupt(tmp_path: Path) -> None:
    target = tmp_path / "thing.json"
    target.write_text("[1,2]", encoding="utf-8")
    assert read_json(target).corrupt is True


def test_a_wrong_version_is_corrupt(tmp_path: Path) -> None:
    target = tmp_path / "thing.json"
    write_atomic(target, {"version": 9, "a": 1})
    assert read_json(target, version=1).corrupt is True


def test_the_right_version_is_usable(tmp_path: Path) -> None:
    target = tmp_path / "thing.json"
    write_atomic(target, {"version": 1, "a": 1})
    assert read_json(target, version=1).usable is True


def test_a_store_round_trips(tmp_path: Path) -> None:
    store = JsonStore(path=tmp_path / "thing.json", version=1)
    store.save({"a": 1})
    assert store.load() == {"version": 1, "a": 1}


def test_a_store_stamps_the_version(tmp_path: Path) -> None:
    store = JsonStore(path=tmp_path / "thing.json", version=4)
    store.save({"a": 1})
    assert json.loads((tmp_path / "thing.json").read_text(encoding="utf-8"))["version"] == 4


def test_a_store_without_a_version_stamps_nothing(tmp_path: Path) -> None:
    store = JsonStore(path=tmp_path / "thing.json")
    store.save({"a": 1})
    assert "version" not in json.loads((tmp_path / "thing.json").read_text(encoding="utf-8"))


def test_a_store_reports_nothing_for_a_missing_file(tmp_path: Path) -> None:
    assert JsonStore(path=tmp_path / "absent.json").load() is None


def test_a_store_counts_corruption(tmp_path: Path) -> None:
    store = JsonStore(path=tmp_path / "thing.json")
    (tmp_path / "thing.json").write_text("{broken", encoding="utf-8")
    assert store.load() is None
    assert store.corrupt == 1


def test_a_missing_file_is_not_counted_as_corrupt(tmp_path: Path) -> None:
    store = JsonStore(path=tmp_path / "absent.json")
    store.load()
    assert store.corrupt == 0


def test_forgetting_removes_the_file(tmp_path: Path) -> None:
    store = JsonStore(path=tmp_path / "thing.json")
    store.save({"a": 1})
    assert store.forget() is True
    assert store.forget() is False
