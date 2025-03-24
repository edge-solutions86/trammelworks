from __future__ import annotations

import json

import pytest

from stanchion.errors import ParseError
from stanchion.ingest.jsonfile import parse_jsonfile_line, render_jsonfile_line
from stanchion.records import Stream
from stanchion.timestamps import NanoTime

TS = "2025-03-04T11:30:00.123456789Z"


def raw(**over: object) -> bytes:
    payload: dict[str, object] = {"log": "auth approved\n", "stream": "stdout", "time": TS}
    payload.update(over)
    return (json.dumps(payload) + "\n").encode("utf-8")


def test_a_complete_record() -> None:
    parsed = parse_jsonfile_line(raw())
    assert parsed.message == "auth approved"
    assert parsed.stream is Stream.STDOUT
    assert parsed.timestamp == NanoTime.parse(TS)
    assert parsed.partial is False
    assert parsed.complete is True


def test_a_record_with_no_trailing_newline_is_partial() -> None:
    parsed = parse_jsonfile_line(raw(log="half of a line"))
    assert parsed.partial is True
    assert parsed.message == "half of a line"


def test_only_the_final_newline_is_removed() -> None:
    parsed = parse_jsonfile_line(raw(log="two\nlines\n"))
    assert parsed.message == "two\nlines"


def test_a_log_field_of_just_a_newline_is_an_empty_complete_line() -> None:
    parsed = parse_jsonfile_line(raw(log="\n"))
    assert parsed.message == ""
    assert parsed.partial is False


def test_an_empty_log_field_is_partial_because_it_has_no_newline() -> None:
    parsed = parse_jsonfile_line(raw(log=""))
    assert parsed.message == ""
    assert parsed.partial is True


def test_stderr_is_read() -> None:
    assert parse_jsonfile_line(raw(stream="stderr")).stream is Stream.STDERR


def test_a_missing_stream_defaults_to_stdout() -> None:
    payload = {"log": "x\n", "time": TS}
    assert parse_jsonfile_line(json.dumps(payload).encode()).stream is Stream.STDOUT


def test_attrs_are_kept_as_strings() -> None:
    parsed = parse_jsonfile_line(raw(attrs={"tag": "pump", "lane": 3}))
    assert parsed.attrs == {"tag": "pump", "lane": "3"}


def test_absent_attrs_give_an_empty_mapping() -> None:
    assert parse_jsonfile_line(raw()).attrs == {}


def test_non_object_attrs_are_refused() -> None:
    with pytest.raises(ParseError, match="attrs is not an object"):
        parse_jsonfile_line(raw(attrs=["a"]))


def test_an_empty_line_is_refused() -> None:
    with pytest.raises(ParseError, match="empty json-file line"):
        parse_jsonfile_line(b"   \n")


def test_a_non_json_line_is_refused() -> None:
    with pytest.raises(ParseError, match="not json"):
        parse_jsonfile_line(b"{not json\n")


def test_a_json_array_is_refused() -> None:
    with pytest.raises(ParseError, match="not an object"):
        parse_jsonfile_line(b"[1, 2]\n")


def test_a_record_without_a_log_field_is_refused() -> None:
    with pytest.raises(ParseError, match="no log field"):
        parse_jsonfile_line(json.dumps({"time": TS}).encode())


def test_a_non_string_log_field_is_refused() -> None:
    with pytest.raises(ParseError, match="log field is not a string"):
        parse_jsonfile_line(raw(log=42))


def test_a_record_without_a_time_is_refused() -> None:
    with pytest.raises(ParseError, match="no time field"):
        parse_jsonfile_line(json.dumps({"log": "x\n"}).encode())


def test_a_bad_time_is_refused() -> None:
    with pytest.raises(ParseError, match="bad time"):
        parse_jsonfile_line(raw(time="yesterday"))


def test_a_bad_stream_is_refused() -> None:
    with pytest.raises(ParseError, match="bad stream"):
        parse_jsonfile_line(raw(stream="stdin"))


def test_the_error_carries_the_position() -> None:
    with pytest.raises(ParseError) as caught:
        parse_jsonfile_line(b"junk", source="/var/lib/docker/x-json.log", offset=64)
    assert caught.value.source == "/var/lib/docker/x-json.log"
    assert caught.value.offset == 64


def test_invalid_utf8_is_replaced_and_reported() -> None:
    body = b'{"log":"frame \xff\xfe\\n","stream":"stdout","time":"' + TS.encode() + b'"}'
    parsed = parse_jsonfile_line(body)
    assert parsed.lossy is True
    assert "�" in parsed.message


def test_valid_multibyte_text_is_not_lossy() -> None:
    parsed = parse_jsonfile_line(raw(log="prix affiché\n"))
    assert parsed.lossy is False
    assert parsed.message == "prix affiché"


def test_render_round_trips_a_complete_record() -> None:
    original = parse_jsonfile_line(raw())
    assert parse_jsonfile_line(render_jsonfile_line(original)) == original


def test_render_round_trips_a_partial_record() -> None:
    original = parse_jsonfile_line(raw(log="no newline here"))
    assert parse_jsonfile_line(render_jsonfile_line(original)) == original


def test_render_round_trips_attrs() -> None:
    original = parse_jsonfile_line(raw(attrs={"tag": "pump"}))
    assert parse_jsonfile_line(render_jsonfile_line(original)).attrs == {"tag": "pump"}


def test_render_puts_the_newline_back_inside_the_log_field() -> None:
    original = parse_jsonfile_line(raw())
    payload = json.loads(render_jsonfile_line(original))
    assert payload["log"] == "auth approved\n"
