from __future__ import annotations

import json
from pathlib import Path

import pytest

from stanchion.clocks import ManualClock
from stanchion.errors import StanchionError
from stanchion.eventlog import EventLog, Level
from stanchion.logsink import RotatingSink, check_outside


def sink(tmp_path: Path, **over: object) -> RotatingSink:
    return RotatingSink(path=tmp_path / "logs" / "stanchion.jsonl", **over)  # type: ignore[arg-type]


def test_a_tiny_cap_is_refused(tmp_path: Path) -> None:
    with pytest.raises(StanchionError, match="below 1 KiB"):
        sink(tmp_path, max_bytes=100)


def test_writing_creates_the_directory(tmp_path: Path) -> None:
    built = sink(tmp_path)
    built.write("one\n")
    built.close()
    assert built.path.exists()


def test_what_was_written_is_there(tmp_path: Path) -> None:
    built = sink(tmp_path)
    built.write("one\n")
    built.write("two\n")
    built.close()
    assert built.path.read_text(encoding="utf-8").splitlines() == ["one", "two"]


def test_the_size_follows_what_was_written(tmp_path: Path) -> None:
    built = sink(tmp_path)
    built.write("one\n")
    assert built.size == 4
    built.close()


def test_a_write_that_would_overflow_rotates_first(tmp_path: Path) -> None:
    built = sink(tmp_path, max_bytes=1024)
    built.write("x" * 1019 + "\n")
    built.write("after\n")
    built.close()
    assert built.rotations == 1
    assert built.path.read_text(encoding="utf-8") == "after\n"


def test_the_previous_file_keeps_what_was_rotated(tmp_path: Path) -> None:
    built = sink(tmp_path, max_bytes=1024)
    built.write("x" * 1019 + "\n")
    built.write("after\n")
    built.close()
    assert built.previous_path.read_text(encoding="utf-8").startswith("x")


def test_only_one_previous_file_is_kept(tmp_path: Path) -> None:
    built = sink(tmp_path, max_bytes=1024)
    for index in range(6):
        built.write(f"{index}" + "x" * 1018 + "\n")
    built.close()
    assert len(list((tmp_path / "logs").iterdir())) == 2


def test_the_newest_rotation_wins(tmp_path: Path) -> None:
    built = sink(tmp_path, max_bytes=1024)
    built.write("first" + "x" * 1014 + "\n")
    built.write("second" + "x" * 1013 + "\n")
    built.write("third\n")
    built.close()
    assert built.previous_path.read_text(encoding="utf-8").startswith("second")


def test_the_total_counts_both_files(tmp_path: Path) -> None:
    built = sink(tmp_path, max_bytes=1024)
    built.write("x" * 1019 + "\n")
    built.write("after\n")
    built.flush()
    assert built.total_bytes > 1000
    built.close()


def test_rotating_by_hand_moves_the_file(tmp_path: Path) -> None:
    built = sink(tmp_path)
    built.write("one\n")
    built.rotate()
    assert built.previous_path.exists()
    assert not built.path.exists()
    assert built.size == 0


def test_rotating_an_empty_sink_is_harmless(tmp_path: Path) -> None:
    built = sink(tmp_path)
    built.rotate()
    assert built.rotations == 1


def test_closing_twice_is_harmless(tmp_path: Path) -> None:
    built = sink(tmp_path)
    built.write("one\n")
    built.close()
    built.close()


def test_reopening_appends(tmp_path: Path) -> None:
    first = sink(tmp_path)
    first.write("one\n")
    first.close()
    second = sink(tmp_path)
    second.write("two\n")
    second.close()
    assert second.path.read_text(encoding="utf-8").splitlines() == ["one", "two"]


def test_an_event_log_writes_through_the_sink(tmp_path: Path) -> None:
    built = sink(tmp_path)
    log = EventLog(sink=built, minimum=Level.DEBUG, clock=ManualClock())  # type: ignore[arg-type]
    log.info("spool_opened", segments=3)
    built.close()
    payload = json.loads(built.path.read_text(encoding="utf-8").strip())
    assert payload["event"] == "spool_opened"
    assert payload["segments"] == 3


def test_an_event_log_rotates_when_it_fills(tmp_path: Path) -> None:
    built = sink(tmp_path, max_bytes=2048)
    log = EventLog(sink=built, minimum=Level.DEBUG, clock=ManualClock())  # type: ignore[arg-type]
    for index in range(200):
        log.info("noise", index=index)
    built.close()
    assert built.rotations >= 1
    assert built.total_bytes < 8192


def test_an_event_log_under_the_container_root_is_refused() -> None:
    with pytest.raises(StanchionError, match="must not live under the container log root"):
        check_outside(Path("/var/log/containers/stanchion.jsonl"), "/var/log/containers")


def test_a_deeper_path_under_the_root_is_also_refused() -> None:
    with pytest.raises(StanchionError, match="must not live under"):
        check_outside(Path("/var/log/containers/deep/stanchion.jsonl"), "/var/log/containers")


def test_an_event_log_elsewhere_is_allowed() -> None:
    check_outside(Path("/var/log/stanchion/stanchion.jsonl"), "/var/log/containers")
