from __future__ import annotations

import json
from pathlib import Path

import pytest

from stanchion.errors import IngestError
from stanchion.ingest.checkpoint import Checkpoint, CheckpointStore, check_key
from stanchion.ingest.tail import FileIdentity
from stanchion.timestamps import NanoTime

KEY = "US-TX-0417_payment-kernel_0"


def checkpoint(**over: object) -> Checkpoint:
    base: dict[str, object] = {
        "key": KEY,
        "path": "/var/log/pods/x/payment-kernel/0.log",
        "device": 2049,
        "inode": 8675309,
        "offset": 40960,
        "updated_at": NanoTime.parse("2025-03-04T11:30:00Z"),
    }
    base.update(over)
    return Checkpoint(**base)  # type: ignore[arg-type]


@pytest.fixture
def store(tmp_path: Path) -> CheckpointStore:
    return CheckpointStore(directory=tmp_path / "ckpt")


@pytest.mark.parametrize("key", [KEY, "a", "a.b-c_d", "0"])
def test_check_key_accepts_a_filename_safe_key(key: str) -> None:
    assert check_key(key) == key


@pytest.mark.parametrize("key", ["", "..", "../escape", "with/slash", ".hidden", "with space"])
def test_check_key_rejects_anything_that_could_escape(key: str) -> None:
    with pytest.raises(IngestError, match="malformed checkpoint key"):
        check_key(key)


def test_loading_an_absent_checkpoint_gives_none(store: CheckpointStore) -> None:
    assert store.load(KEY) is None


def test_a_saved_checkpoint_comes_back(store: CheckpointStore) -> None:
    store.save(checkpoint())
    assert store.load(KEY) == checkpoint()


def test_save_creates_the_directory(tmp_path: Path) -> None:
    store = CheckpointStore(directory=tmp_path / "deep" / "ckpt")
    store.save(checkpoint())
    assert (tmp_path / "deep" / "ckpt").is_dir()


def test_save_leaves_no_temporary_files(store: CheckpointStore) -> None:
    store.save(checkpoint())
    assert [p.suffix for p in store.directory.iterdir()] == [".ckpt"]


def test_saving_twice_replaces(store: CheckpointStore) -> None:
    store.save(checkpoint())
    store.save(checkpoint(offset=81920))
    loaded = store.load(KEY)
    assert loaded is not None
    assert loaded.offset == 81920


def test_a_negative_offset_is_refused(store: CheckpointStore) -> None:
    with pytest.raises(IngestError, match="negative offset"):
        store.save(checkpoint(offset=-1))


def test_an_offset_of_zero_is_fine(store: CheckpointStore) -> None:
    store.save(checkpoint(offset=0))
    loaded = store.load(KEY)
    assert loaded is not None
    assert loaded.offset == 0


def test_the_nanosecond_stamp_survives(store: CheckpointStore) -> None:
    store.save(checkpoint(updated_at=NanoTime.parse("2025-03-04T11:30:00.000000042Z")))
    loaded = store.load(KEY)
    assert loaded is not None
    assert loaded.updated_at.nanos_in_second == 42


def test_identity_is_rebuilt_from_the_stored_pair() -> None:
    assert checkpoint().identity == FileIdentity(device=2049, inode=8675309)


def test_matches_is_true_for_the_same_file() -> None:
    assert checkpoint().matches(FileIdentity(device=2049, inode=8675309)) is True


def test_matches_is_false_after_a_rotation() -> None:
    assert checkpoint().matches(FileIdentity(device=2049, inode=42)) is False


def test_a_corrupt_checkpoint_reads_as_absent(store: CheckpointStore) -> None:
    store.save(checkpoint())
    (store.directory / f"{KEY}.ckpt").write_text("{not json", encoding="utf-8")
    assert store.load(KEY) is None
    assert store.corrupt == 1


def test_a_truncated_checkpoint_reads_as_absent(store: CheckpointStore) -> None:
    store.save(checkpoint())
    target = store.directory / f"{KEY}.ckpt"
    target.write_text(target.read_text(encoding="utf-8")[:20], encoding="utf-8")
    assert store.load(KEY) is None
    assert store.corrupt == 1


def test_a_checkpoint_that_is_not_an_object_reads_as_absent(store: CheckpointStore) -> None:
    store.directory.mkdir(parents=True, exist_ok=True)
    (store.directory / f"{KEY}.ckpt").write_text("[1,2,3]", encoding="utf-8")
    assert store.load(KEY) is None
    assert store.corrupt == 1


def test_a_checkpoint_missing_a_field_reads_as_absent(store: CheckpointStore) -> None:
    store.directory.mkdir(parents=True, exist_ok=True)
    payload = checkpoint().as_dict()
    del payload["offset"]
    (store.directory / f"{KEY}.ckpt").write_text(json.dumps(payload), encoding="utf-8")
    assert store.load(KEY) is None
    assert store.corrupt == 1


def test_a_future_version_reads_as_absent(store: CheckpointStore) -> None:
    store.directory.mkdir(parents=True, exist_ok=True)
    payload = checkpoint().as_dict()
    payload["version"] = 99
    (store.directory / f"{KEY}.ckpt").write_text(json.dumps(payload), encoding="utf-8")
    assert store.load(KEY) is None
    assert store.corrupt == 1


def test_from_dict_names_the_version_it_refused() -> None:
    payload = checkpoint().as_dict()
    payload["version"] = 7
    with pytest.raises(IngestError, match="unknown checkpoint version"):
        Checkpoint.from_dict(payload)


def test_the_stored_form_round_trips() -> None:
    assert Checkpoint.from_dict(checkpoint().as_dict()) == checkpoint()


def test_forget_removes_a_checkpoint(store: CheckpointStore) -> None:
    store.save(checkpoint())
    assert store.forget(KEY) is True
    assert store.load(KEY) is None


def test_forget_reports_when_there_was_nothing(store: CheckpointStore) -> None:
    assert store.forget(KEY) is False


def test_keys_lists_what_is_stored(store: CheckpointStore) -> None:
    store.save(checkpoint())
    store.save(checkpoint(key="US-TX-0417_tank-gauge_0"))
    assert store.keys() == ("US-TX-0417_payment-kernel_0", "US-TX-0417_tank-gauge_0")


def test_keys_of_an_absent_directory_is_empty(tmp_path: Path) -> None:
    assert CheckpointStore(directory=tmp_path / "nope").keys() == ()


def test_sweep_removes_only_what_is_no_longer_followed(store: CheckpointStore) -> None:
    store.save(checkpoint())
    store.save(checkpoint(key="US-TX-0417_tank-gauge_0"))
    removed = store.sweep({"US-TX-0417_payment-kernel_0"})
    assert removed == 1
    assert store.keys() == ("US-TX-0417_payment-kernel_0",)


def test_sweep_with_everything_live_removes_nothing(store: CheckpointStore) -> None:
    store.save(checkpoint())
    assert store.sweep({KEY}) == 0
