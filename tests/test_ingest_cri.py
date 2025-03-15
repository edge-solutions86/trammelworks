from __future__ import annotations

import pytest

from stanchion.errors import ParseError
from stanchion.ingest.cri import CriLine, parse_cri_line, parse_tags, render_cri_line
from stanchion.records import Stream
from stanchion.timestamps import NanoTime

TS = "2025-03-04T11:30:00.123456789Z"


def line(text: str) -> bytes:
    return text.encode("utf-8")


def test_a_complete_stdout_line() -> None:
    parsed = parse_cri_line(line(f"{TS} stdout F auth approved"))
    assert parsed.timestamp == NanoTime.parse(TS)
    assert parsed.stream is Stream.STDOUT
    assert parsed.message == "auth approved"
    assert parsed.partial is False
    assert parsed.complete is True
    assert parsed.lossy is False


def test_a_partial_line_is_flagged() -> None:
    parsed = parse_cri_line(line(f"{TS} stdout P half of a"))
    assert parsed.partial is True
    assert parsed.complete is False


def test_stderr_is_read() -> None:
    assert parse_cri_line(line(f"{TS} stderr F boom")).stream is Stream.STDERR


def test_the_trailing_newline_is_not_part_of_the_message() -> None:
    assert parse_cri_line(line(f"{TS} stdout F hello\n")).message == "hello"


def test_a_windows_terminator_is_also_stripped() -> None:
    assert parse_cri_line(line(f"{TS} stdout F hello\r\n")).message == "hello"


def test_spaces_inside_the_message_survive() -> None:
    parsed = parse_cri_line(line(f"{TS} stdout F pump 3 nozzle 1 lifted"))
    assert parsed.message == "pump 3 nozzle 1 lifted"


def test_a_message_may_be_empty() -> None:
    assert parse_cri_line(line(f"{TS} stdout F ")).message == ""


def test_a_message_that_is_only_spaces_is_kept_verbatim() -> None:
    assert parse_cri_line(line(f"{TS} stdout F    ")).message == "   "


def test_the_nanosecond_digits_survive() -> None:
    parsed = parse_cri_line(line(f"{TS} stdout F x"))
    assert parsed.timestamp.nanos_in_second == 123456789


def test_extra_tags_are_kept() -> None:
    parsed = parse_cri_line(line(f"{TS} stdout F:someflag x"))
    assert parsed.tags == ("someflag",)
    assert parsed.partial is False


def test_extra_tags_on_a_partial_chunk() -> None:
    parsed = parse_cri_line(line(f"{TS} stdout P:a:b x"))
    assert parsed.partial is True
    assert parsed.tags == ("a", "b")


def test_parse_tags_reads_the_head() -> None:
    assert parse_tags("F") == (False, ())
    assert parse_tags("P") == (True, ())


def test_parse_tags_rejects_an_unknown_head() -> None:
    with pytest.raises(ParseError, match="unknown cri tag"):
        parse_tags("Z")


def test_parse_tags_rejects_an_empty_field() -> None:
    with pytest.raises(ParseError, match="empty cri tag"):
        parse_tags(":")


def test_an_empty_line_is_refused() -> None:
    with pytest.raises(ParseError, match="empty cri line"):
        parse_cri_line(b"\n")


@pytest.mark.parametrize(
    "text",
    [f"{TS} stdout", f"{TS}", "garbage"],
)
def test_a_short_line_is_refused(text: str) -> None:
    with pytest.raises(ParseError, match="too few fields"):
        parse_cri_line(line(text))


def test_a_bad_timestamp_is_refused() -> None:
    with pytest.raises(ParseError, match="no timestamp"):
        parse_cri_line(line("yesterday stdout F x"))


def test_a_bad_stream_is_refused() -> None:
    with pytest.raises(ParseError, match="no stream"):
        parse_cri_line(line(f"{TS} stdin F x"))


def test_a_bad_tag_is_refused() -> None:
    with pytest.raises(ParseError, match="bad tag"):
        parse_cri_line(line(f"{TS} stdout Q x"))


def test_the_error_carries_the_position_it_was_given() -> None:
    with pytest.raises(ParseError) as caught:
        parse_cri_line(b"junk", source="/var/log/pods/a/0.log", offset=8192)
    assert caught.value.source == "/var/log/pods/a/0.log"
    assert caught.value.offset == 8192


def test_invalid_utf8_in_the_message_is_replaced_and_reported() -> None:
    raw = f"{TS} stdout F frame ".encode() + b"\xff\xfe" + b" end"
    parsed = parse_cri_line(raw)
    assert parsed.lossy is True
    assert parsed.message.startswith("frame ")
    assert parsed.message.endswith(" end")
    assert "�" in parsed.message


def test_valid_multibyte_utf8_is_not_lossy() -> None:
    parsed = parse_cri_line(f"{TS} stdout F prix affiché".encode())
    assert parsed.lossy is False
    assert parsed.message == "prix affiché"


def test_a_tab_in_the_message_is_not_a_separator() -> None:
    parsed = parse_cri_line(line(f"{TS} stdout F a\tb"))
    assert parsed.message == "a\tb"


def test_render_round_trips_a_complete_line() -> None:
    original = parse_cri_line(line(f"{TS} stdout F auth approved"))
    assert parse_cri_line(render_cri_line(original)) == original


def test_render_round_trips_a_partial_line_with_tags() -> None:
    original = parse_cri_line(line(f"{TS} stderr P:x chunk"))
    assert parse_cri_line(render_cri_line(original)) == original


def test_render_ends_with_a_newline() -> None:
    rendered = render_cri_line(
        CriLine(timestamp=NanoTime.parse(TS), stream=Stream.STDOUT, message="x")
    )
    assert rendered.endswith(b"\n")
    assert rendered.count(b"\n") == 1
