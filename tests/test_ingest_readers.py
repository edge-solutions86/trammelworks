from __future__ import annotations

import json

import pytest

from stanchion.ingest.readers import CriReader, FragmentReader, JournalReader, JsonFileReader
from stanchion.records import Stream
from stanchion.timestamps import NanoTime

PATH = "/var/log/pods/forecourt_x_uid/payment-kernel/0.log"
TS = "2025-03-04T11:30:00.123456789Z"


def cri(message: str, tag: str = "F", stream: str = "stdout") -> bytes:
    return f"{TS} {stream} {tag} {message}\n".encode()


def jsonl(message: str, stream: str = "stdout") -> bytes:
    return (json.dumps({"log": message, "stream": stream, "time": TS}) + "\n").encode()


def journal(message: str, micros: int = 1741087800123456) -> bytes:
    return f"__REALTIME_TIMESTAMP={micros}\nMESSAGE={message}\n\n".encode()


@pytest.mark.parametrize("reader", [CriReader(), JsonFileReader(), JournalReader()])
def test_every_reader_satisfies_the_protocol(reader: FragmentReader) -> None:
    assert hasattr(reader, "read")
    assert reader.read(b"", path=PATH).consumed == 0


def test_cri_reads_two_lines() -> None:
    result = CriReader().read(cri("one") + cri("two"), path=PATH)
    assert [f.message for f in result.fragments] == ["one", "two"]
    assert result.consumed == len(cri("one")) + len(cri("two"))


def test_cri_leaves_a_half_written_line_alone() -> None:
    data = cri("one") + f"{TS} stdout F unfinis".encode()
    result = CriReader().read(data, path=PATH)
    assert [f.message for f in result.fragments] == ["one"]
    assert result.consumed == len(cri("one"))


def test_cri_consumed_is_zero_when_nothing_is_complete() -> None:
    assert CriReader().read(b"no terminator yet", path=PATH).consumed == 0


def test_cri_records_the_offset_of_each_line() -> None:
    result = CriReader().read(cri("one") + cri("two"), path=PATH)
    assert result.fragments[0].source.offset == 0
    assert result.fragments[1].source.offset == len(cri("one"))


def test_cri_applies_the_base_offset() -> None:
    result = CriReader().read(cri("one"), path=PATH, base_offset=4096)
    assert result.fragments[0].source.offset == 4096


def test_cri_source_length_includes_the_terminator() -> None:
    line = cri("one")
    result = CriReader().read(line, path=PATH)
    assert result.fragments[0].source.length == len(line)


def test_cri_keeps_the_partial_flag() -> None:
    result = CriReader().read(cri("half", tag="P"), path=PATH)
    assert result.fragments[0].partial is True


def test_cri_keeps_the_stream() -> None:
    result = CriReader().read(cri("boom", stream="stderr"), path=PATH)
    assert result.fragments[0].stream is Stream.STDERR


def test_cri_keeps_the_nanosecond_timestamp() -> None:
    result = CriReader().read(cri("one"), path=PATH)
    assert result.fragments[0].observed_at == NanoTime.parse(TS)


def test_cri_skips_a_bad_line_and_carries_on() -> None:
    result = CriReader().read(cri("one") + b"garbage\n" + cri("three"), path=PATH)
    assert [f.message for f in result.fragments] == ["one", "three"]
    assert result.skipped == 1
    assert len(result.errors) == 1


def test_a_skipped_line_is_still_consumed() -> None:
    data = cri("one") + b"garbage\n"
    result = CriReader().read(data, path=PATH)
    assert result.consumed == len(data)


def test_the_error_names_the_absolute_offset() -> None:
    result = CriReader().read(b"garbage\n", path=PATH, base_offset=1000)
    assert result.errors[0].offset == 1000
    assert result.errors[0].source == PATH


def test_cri_skips_a_blank_line_without_an_error() -> None:
    result = CriReader().read(b"\n" + cri("one"), path=PATH)
    assert result.skipped == 1
    assert result.errors == []


def test_jsonfile_reads_records() -> None:
    result = JsonFileReader().read(jsonl("one\n") + jsonl("two\n"), path=PATH)
    assert [f.message for f in result.fragments] == ["one", "two"]


def test_jsonfile_marks_a_record_without_a_newline_partial() -> None:
    result = JsonFileReader().read(jsonl("half"), path=PATH)
    assert result.fragments[0].partial is True


def test_jsonfile_leaves_an_incomplete_record_alone() -> None:
    data = jsonl("one\n") + b'{"log": "two'
    result = JsonFileReader().read(data, path=PATH)
    assert len(result.fragments) == 1
    assert result.consumed == len(jsonl("one\n"))


def test_jsonfile_skips_a_broken_record() -> None:
    result = JsonFileReader().read(b"{oops}\n" + jsonl("ok\n"), path=PATH)
    assert [f.message for f in result.fragments] == ["ok"]
    assert result.skipped == 1


def test_journal_reads_a_block() -> None:
    result = JournalReader().read(journal("auth approved"), path=PATH)
    assert [f.message for f in result.fragments] == ["auth approved"]


def test_journal_reads_two_blocks() -> None:
    result = JournalReader().read(journal("one") + journal("two"), path=PATH)
    assert len(result.fragments) == 2


def test_journal_leaves_an_unterminated_block_alone() -> None:
    data = journal("one") + b"__REALTIME_TIMESTAMP=1741087800123456\nMESSAGE=two\n"
    result = JournalReader().read(data, path=PATH)
    assert len(result.fragments) == 1
    assert result.consumed == len(journal("one"))


def test_journal_entries_are_never_partial() -> None:
    result = JournalReader().read(journal("x"), path=PATH)
    assert result.fragments[0].partial is False


def test_journal_reads_the_realtime_stamp() -> None:
    result = JournalReader().read(journal("x", micros=1741087800000000), path=PATH)
    assert result.fragments[0].observed_at == NanoTime.parse("2025-03-04T11:30:00Z")


def test_journal_skips_a_block_with_no_timestamp() -> None:
    result = JournalReader().read(b"MESSAGE=orphan\n\n" + journal("good"), path=PATH)
    assert [f.message for f in result.fragments] == ["good"]
    assert result.skipped == 1


def test_journal_maps_priority_to_the_stream() -> None:
    block = b"__REALTIME_TIMESTAMP=1741087800123456\nPRIORITY=3\nMESSAGE=boom\n\n"
    result = JournalReader().read(block, path=PATH)
    assert result.fragments[0].stream is Stream.STDERR


def test_reader_for_picks_the_cri_reader() -> None:
    from stanchion.ingest.detect import Format
    from stanchion.ingest.readers import reader_for

    result = reader_for(Format.CRI).read(cri("one"), path=PATH)
    assert [f.message for f in result.fragments] == ["one"]


def test_reader_for_picks_the_jsonfile_reader() -> None:
    from stanchion.ingest.detect import Format
    from stanchion.ingest.readers import reader_for

    result = reader_for(Format.JSONFILE).read(jsonl("one\n"), path=PATH)
    assert [f.message for f in result.fragments] == ["one"]


def test_reader_for_picks_the_journal_reader() -> None:
    from stanchion.ingest.detect import Format
    from stanchion.ingest.readers import reader_for

    result = reader_for(Format.JOURNAL).read(journal("one"), path=PATH)
    assert [f.message for f in result.fragments] == ["one"]


def test_the_two_line_readers_share_one_walk() -> None:
    from stanchion.ingest.readers import LineReader

    assert isinstance(CriReader(), LineReader)
    assert isinstance(JsonFileReader(), LineReader)


def test_the_shared_walk_reports_the_same_consumed_count() -> None:
    left = CriReader().read(cri("one") + b"partial", path=PATH)
    right = JsonFileReader().read(jsonl("one\n") + b"partial", path=PATH)
    assert left.consumed == len(cri("one"))
    assert right.consumed == len(jsonl("one\n"))


def journal_positioned(message: str) -> bytes:
    fields = [
        "__REALTIME_TIMESTAMP=1741087800123456",
        "__MONOTONIC_TIMESTAMP=98765432",
        "_BOOT_ID=4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d",
        f"MESSAGE={message}",
        "",
        "",
    ]
    return chr(10).join(fields).encode()


def test_journal_passes_the_monotonic_position_through() -> None:
    result = JournalReader().read(journal_positioned("auth"), path=PATH)
    assert result.fragments[0].labels["monotonic_ns"] == str(98765432 * 1000)


def test_journal_passes_the_boot_id_through() -> None:
    result = JournalReader().read(journal_positioned("auth"), path=PATH)
    assert result.fragments[0].labels["boot_id"] == "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d"


def test_an_entry_with_no_position_carries_no_labels() -> None:
    result = JournalReader().read(journal("plain"), path=PATH)
    assert result.fragments[0].labels == {}


def test_the_cri_reader_leaves_labels_empty() -> None:
    result = CriReader().read(cri("one"), path=PATH)
    assert result.fragments[0].labels == {}
