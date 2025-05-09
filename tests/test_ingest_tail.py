from __future__ import annotations

from pathlib import Path

import pytest

from stanchion.errors import IngestError
from stanchion.ingest.readers import CriReader
from stanchion.ingest.tail import FileIdentity, Tailer, TailEvent

TS = "2025-03-04T11:30:00.123456789Z"


def cri(message: str, tag: str = "F") -> str:
    return f"{TS} stdout {tag} {message}\n"


def append(path: Path, text: str) -> None:
    with path.open("a", encoding="utf-8", newline="") as handle:
        handle.write(text)


@pytest.fixture
def logfile(tmp_path: Path) -> Path:
    target = tmp_path / "0.log"
    target.touch()
    return target


def tailer(path: Path, **over: object) -> Tailer:
    return Tailer(path=path, reader=CriReader(), **over)  # type: ignore[arg-type]


def test_an_empty_file_yields_nothing(logfile: Path) -> None:
    result = tailer(logfile).poll()
    assert result.fragments == []
    assert result.event is TailEvent.NONE
    assert result.empty is True


def test_a_missing_file_that_was_never_seen_is_quiet(tmp_path: Path) -> None:
    result = tailer(tmp_path / "absent.log").poll()
    assert result.event is TailEvent.NONE


def test_the_first_poll_reads_what_is_there(logfile: Path) -> None:
    append(logfile, cri("one") + cri("two"))
    result = tailer(logfile).poll()
    assert [f.message for f in result.fragments] == ["one", "two"]
    assert result.event is TailEvent.GROWN


def test_a_second_poll_reads_only_what_is_new(logfile: Path) -> None:
    tail = tailer(logfile)
    append(logfile, cri("one"))
    tail.poll()
    append(logfile, cri("two"))
    result = tail.poll()
    assert [f.message for f in result.fragments] == ["two"]


def test_a_poll_with_no_change_reads_nothing(logfile: Path) -> None:
    tail = tailer(logfile)
    append(logfile, cri("one"))
    tail.poll()
    assert tail.poll().empty is True


def test_the_offset_advances_by_what_was_read(logfile: Path) -> None:
    tail = tailer(logfile)
    text = cri("one")
    append(logfile, text)
    tail.poll()
    assert tail.offset == len(text)


def test_a_half_written_line_is_held_back(logfile: Path) -> None:
    tail = tailer(logfile)
    append(logfile, cri("one") + f"{TS} stdout F unfin")
    result = tail.poll()
    assert [f.message for f in result.fragments] == ["one"]
    assert tail.buffered > 0


def test_the_held_bytes_join_the_next_read(logfile: Path) -> None:
    tail = tailer(logfile)
    append(logfile, cri("one") + f"{TS} stdout F unfin")
    tail.poll()
    append(logfile, "ished\n")
    result = tail.poll()
    assert [f.message for f in result.fragments] == ["unfinished"]
    assert tail.buffered == 0


def test_offsets_are_absolute_across_polls(logfile: Path) -> None:
    tail = tailer(logfile)
    first = cri("one")
    append(logfile, first)
    tail.poll()
    append(logfile, cri("two"))
    result = tail.poll()
    assert result.fragments[0].source.offset == len(first)


def test_an_offset_is_right_even_when_a_line_spanned_two_polls(logfile: Path) -> None:
    tail = tailer(logfile)
    head = f"{TS} stdout F split"
    append(logfile, head)
    tail.poll()
    append(logfile, "line\n")
    result = tail.poll()
    assert result.fragments[0].source.offset == 0


def test_a_small_chunk_size_needs_several_polls(logfile: Path) -> None:
    tail = tailer(logfile, chunk_size=16)
    append(logfile, cri("one") + cri("two"))
    seen: list[str] = []
    for _ in range(20):
        seen.extend(f.message for f in tail.poll().fragments)
    assert seen == ["one", "two"]


def test_a_non_positive_chunk_size_is_refused(logfile: Path) -> None:
    with pytest.raises(IngestError, match="chunk_size must be positive"):
        tailer(logfile, chunk_size=0)


def test_truncation_in_place_is_noticed(logfile: Path) -> None:
    tail = tailer(logfile)
    append(logfile, cri("one") + cri("two"))
    tail.poll()
    logfile.write_bytes(b"")
    append(logfile, cri("fresh"))
    result = tail.poll()
    assert result.event is TailEvent.TRUNCATED
    assert [f.message for f in result.fragments] == ["fresh"]


def test_truncation_resets_the_offset(logfile: Path) -> None:
    tail = tailer(logfile)
    append(logfile, cri("one"))
    tail.poll()
    logfile.write_bytes(b"")
    tail.poll()
    assert tail.offset == 0


def test_truncation_reports_the_bytes_it_had_buffered(logfile: Path) -> None:
    tail = tailer(logfile)
    append(logfile, f"{TS} stdout F half")
    tail.poll()
    assert tail.buffered > 0
    logfile.write_bytes(b"")
    result = tail.poll()
    assert result.lost_bytes > 0


def test_a_rename_rotation_is_noticed(logfile: Path) -> None:
    tail = tailer(logfile)
    append(logfile, cri("one"))
    tail.poll()
    logfile.rename(logfile.with_name("0.log.1"))
    logfile.touch()
    append(logfile, cri("after"))
    result = tail.poll()
    assert result.event is TailEvent.ROTATED
    assert [f.message for f in result.fragments] == ["after"]


def test_a_rotation_starts_the_new_file_at_zero(logfile: Path) -> None:
    tail = tailer(logfile)
    append(logfile, cri("one") + cri("two") + cri("three"))
    tail.poll()
    logfile.rename(logfile.with_name("0.log.1"))
    logfile.touch()
    append(logfile, cri("after"))
    result = tail.poll()
    assert result.fragments[0].source.offset == 0


def test_a_deleted_file_is_reported_once(logfile: Path) -> None:
    tail = tailer(logfile)
    append(logfile, cri("one"))
    tail.poll()
    logfile.unlink()
    assert tail.poll().event is TailEvent.MISSING
    assert tail.poll().event is TailEvent.NONE


def test_a_file_that_comes_back_is_read_from_the_start(logfile: Path) -> None:
    tail = tailer(logfile)
    append(logfile, cri("one"))
    tail.poll()
    logfile.unlink()
    tail.poll()
    logfile.touch()
    append(logfile, cri("again"))
    result = tail.poll()
    assert [f.message for f in result.fragments] == ["again"]


def test_seek_to_resumes_from_a_checkpoint(logfile: Path) -> None:
    first = cri("one")
    append(logfile, first + cri("two"))
    tail = tailer(logfile)
    tail.seek_to(len(first), FileIdentity.of(logfile))
    result = tail.poll()
    assert [f.message for f in result.fragments] == ["two"]


def test_seek_to_refuses_a_negative_offset(logfile: Path) -> None:
    with pytest.raises(IngestError, match="negative offset"):
        tailer(logfile).seek_to(-1)


def test_seek_to_clears_any_held_bytes(logfile: Path) -> None:
    tail = tailer(logfile)
    append(logfile, f"{TS} stdout F half")
    tail.poll()
    tail.seek_to(0)
    assert tail.buffered == 0


def test_file_identity_of_the_same_file_matches(logfile: Path) -> None:
    assert FileIdentity.of(logfile) == FileIdentity.of(logfile)


def test_file_identity_differs_between_two_files(tmp_path: Path) -> None:
    left, right = tmp_path / "a.log", tmp_path / "b.log"
    left.touch()
    right.touch()
    assert FileIdentity.of(left) != FileIdentity.of(right)


def test_a_bad_line_is_counted_and_skipped(logfile: Path) -> None:
    append(logfile, cri("one") + "garbage\n" + cri("three"))
    result = tailer(logfile).poll()
    assert [f.message for f in result.fragments] == ["one", "three"]
    assert result.skipped == 1
    assert len(result.errors) == 1
