from __future__ import annotations

import struct

import pytest

from stanchion.errors import ParseError
from stanchion.ingest.journal import (
    JournalEntry,
    parse_journal_export,
    render_journal_entry,
)
from stanchion.records import Stream
from stanchion.timestamps import NanoTime

BOOT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d"

TEXT_ENTRY = (
    b"__REALTIME_TIMESTAMP=1741087800123456\n"
    b"__MONOTONIC_TIMESTAMP=98765432100\n"
    b"_BOOT_ID=" + BOOT.encode() + b"\n"
    b"PRIORITY=6\n"
    b"CONTAINER_NAME=payment-kernel\n"
    b"MESSAGE=auth approved\n"
    b"\n"
)


def binary_field(name: str, value: str) -> bytes:
    encoded = value.encode("utf-8")
    return name.encode() + b"\n" + struct.pack("<Q", len(encoded)) + encoded + b"\n"


def test_one_text_entry() -> None:
    entries = parse_journal_export(TEXT_ENTRY)
    assert len(entries) == 1
    entry = entries[0]
    assert entry.message == "auth approved"
    assert entry.unit == "payment-kernel"
    assert entry.boot_id == BOOT


def test_realtime_is_read_as_microseconds() -> None:
    entry = parse_journal_export(TEXT_ENTRY)[0]
    assert entry.realtime == NanoTime.parse("2025-03-04T11:30:00.123456Z")


def test_monotonic_is_read() -> None:
    assert parse_journal_export(TEXT_ENTRY)[0].monotonic_us == 98765432100


def test_an_absent_monotonic_is_none() -> None:
    assert JournalEntry(fields={"MESSAGE": "x"}).monotonic_us is None


def test_a_missing_realtime_is_an_error() -> None:
    with pytest.raises(ParseError, match="no realtime timestamp"):
        _ = JournalEntry(fields={"MESSAGE": "x"}).realtime


def test_a_non_numeric_realtime_is_an_error() -> None:
    with pytest.raises(ParseError, match="realtime is not an integer"):
        _ = JournalEntry(fields={"__REALTIME_TIMESTAMP": "soon"}).realtime


def test_a_non_numeric_monotonic_is_an_error() -> None:
    with pytest.raises(ParseError, match="monotonic is not an integer"):
        _ = JournalEntry(fields={"__MONOTONIC_TIMESTAMP": "soon"}).monotonic_us


def test_priority_six_is_stdout() -> None:
    assert parse_journal_export(TEXT_ENTRY)[0].stream is Stream.STDOUT


@pytest.mark.parametrize("priority", ["0", "1", "2", "3"])
def test_priority_at_or_below_error_is_stderr(priority: str) -> None:
    entry = JournalEntry(fields={"PRIORITY": priority})
    assert entry.stream is Stream.STDERR


@pytest.mark.parametrize("priority", ["4", "5", "6", "7"])
def test_priority_above_error_is_stdout(priority: str) -> None:
    assert JournalEntry(fields={"PRIORITY": priority}).stream is Stream.STDOUT


def test_a_missing_priority_defaults_to_stdout() -> None:
    assert JournalEntry(fields={"MESSAGE": "x"}).stream is Stream.STDOUT


def test_a_non_numeric_priority_is_an_error() -> None:
    with pytest.raises(ParseError, match="priority is not an integer"):
        _ = JournalEntry(fields={"PRIORITY": "high"}).stream


def test_two_entries_are_split_on_the_blank_line() -> None:
    entries = parse_journal_export(TEXT_ENTRY + TEXT_ENTRY)
    assert len(entries) == 2


def test_a_trailing_entry_without_a_blank_line_is_still_returned() -> None:
    entries = parse_journal_export(b"MESSAGE=last one\n")
    assert len(entries) == 1
    assert entries[0].message == "last one"


def test_repeated_blank_lines_do_not_make_empty_entries() -> None:
    assert len(parse_journal_export(b"\n\n" + TEXT_ENTRY + b"\n\n")) == 1


def test_an_empty_stream_gives_no_entries() -> None:
    assert parse_journal_export(b"") == []


def test_a_binary_field_carries_its_newlines() -> None:
    trace = "Traceback:\n  line one\n  line two"
    data = b"__REALTIME_TIMESTAMP=1741087800000000\n" + binary_field("MESSAGE", trace) + b"\n"
    entry = parse_journal_export(data)[0]
    assert entry.message == trace
    assert "MESSAGE" in entry.binary


def test_a_binary_field_does_not_end_the_entry() -> None:
    data = (
        b"__REALTIME_TIMESTAMP=1741087800000000\n"
        + binary_field("MESSAGE", "one\ntwo")
        + b"CONTAINER_NAME=payment-kernel\n\n"
    )
    entry = parse_journal_export(data)[0]
    assert entry.unit == "payment-kernel"


def test_a_truncated_binary_header_is_refused() -> None:
    with pytest.raises(ParseError, match="binary field is truncated"):
        parse_journal_export(b"MESSAGE\n\x01\x02")


def test_a_binary_value_that_overruns_is_refused() -> None:
    data = b"MESSAGE\n" + struct.pack("<Q", 999) + b"short"
    with pytest.raises(ParseError, match="runs past the end"):
        parse_journal_export(data)


def test_a_value_containing_an_equals_sign_is_kept_whole() -> None:
    entry = parse_journal_export(b"MESSAGE=a=b=c\n\n")[0]
    assert entry.message == "a=b=c"


def test_invalid_utf8_in_a_value_is_replaced() -> None:
    entry = parse_journal_export(b"MESSAGE=frame \xff\xfe\n\n")[0]
    assert "�" in entry.message


def test_get_returns_the_default_for_an_absent_field() -> None:
    assert JournalEntry().get("MESSAGE", "none") == "none"


def test_render_round_trips_a_text_entry() -> None:
    original = parse_journal_export(TEXT_ENTRY)[0]
    assert parse_journal_export(render_journal_entry(original))[0] == original


def test_render_uses_the_binary_form_for_a_multiline_value() -> None:
    entry = JournalEntry(fields={"MESSAGE": "one\ntwo"})
    rendered = render_journal_entry(entry)
    assert b"MESSAGE\n" in rendered
    assert parse_journal_export(rendered)[0].message == "one\ntwo"


def test_render_keeps_a_field_binary_once_it_was_read_that_way() -> None:
    entry = JournalEntry(fields={"MESSAGE": "plain"}, binary=frozenset({"MESSAGE"}))
    rendered = render_journal_entry(entry)
    assert b"MESSAGE=plain" not in rendered
    assert parse_journal_export(rendered)[0].message == "plain"
