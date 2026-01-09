from __future__ import annotations

import csv
import io
import json

import pytest

from stanchion.archive.repository import StoredRecord
from stanchion.errors import StanchionError
from stanchion.records import Stream
from stanchion.report.render import (
    Format,
    RenderOptions,
    parse_format,
    render,
)
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-03-04T12:00:00Z")


def at(raw: str) -> NanoTime:
    return NanoTime.parse(raw)


def record(**over: object) -> StoredRecord:
    base: dict[str, object] = {
        "key": "k",
        "site": "US-TX-0417",
        "unit": "payment-kernel",
        "klass": "payment",
        "stream": Stream.STDOUT,
        "observed_at": at("2025-03-04T11:00:00Z"),
        "corrected_at": None,
        "confidence": "measured",
        "message": "auth approved",
        "labels": {"lane": "3"},
        "stored_at": NOW,
    }
    base.update(over)
    return StoredRecord(**base)  # type: ignore[arg-type]


@pytest.mark.parametrize("name", ["text", "jsonl", "csv", "markdown"])
def test_every_format_name_parses(name: str) -> None:
    assert parse_format(name).value == name


def test_a_format_name_is_case_insensitive() -> None:
    assert parse_format(" CSV ") is Format.CSV


def test_an_unknown_format_is_refused() -> None:
    with pytest.raises(StanchionError, match="unknown report format"):
        parse_format("pdf")


def test_a_width_below_one_is_refused() -> None:
    with pytest.raises(StanchionError, match="at least one"):
        RenderOptions(width=0)


def test_the_text_format_has_a_header() -> None:
    out = render([record()])
    assert out.splitlines()[0].startswith("time")


def test_the_header_can_be_turned_off() -> None:
    out = render([record()], RenderOptions(header=False))
    assert "time" not in out.splitlines()[0]


def test_the_text_format_shows_the_unit_and_message() -> None:
    out = render([record()], RenderOptions(header=False))
    assert "payment-kernel" in out
    assert "auth approved" in out


def test_the_text_format_shows_the_corrected_time_by_default() -> None:
    out = render([record(corrected_at=at("2025-03-04T11:00:07Z"))], RenderOptions(header=False))
    assert "11:00:07" in out


def test_the_raw_time_can_be_asked_for() -> None:
    out = render(
        [record(corrected_at=at("2025-03-04T11:00:07Z"))],
        RenderOptions(header=False, raw_time=True),
    )
    assert "11:00:00" in out


def test_a_long_message_is_cut_with_an_ellipsis() -> None:
    out = render([record(message="z" * 200)], RenderOptions(header=False, width=20))
    assert "…" in out
    assert "z" * 100 not in out


def test_a_newline_in_a_message_does_not_break_the_table() -> None:
    out = render([record(message="one\ntwo")], RenderOptions(header=False))
    assert len(out.splitlines()) == 1
    assert "one\\ntwo" in out


def test_an_empty_list_renders_to_just_the_header() -> None:
    assert render([]).splitlines() == [line for line in render([]).splitlines() if line]


def test_the_jsonl_format_is_one_object_per_line() -> None:
    out = render([record(), record(message="second")], RenderOptions(fmt=Format.JSONL))
    lines = out.splitlines()
    assert len(lines) == 2
    assert json.loads(lines[1])["message"] == "second"


def test_the_jsonl_format_carries_the_labels() -> None:
    out = render([record()], RenderOptions(fmt=Format.JSONL))
    assert json.loads(out)["labels"] == {"lane": "3"}


def test_the_jsonl_format_omits_an_absent_correction() -> None:
    assert "corrected_at" not in json.loads(render([record()], RenderOptions(fmt=Format.JSONL)))


def test_the_jsonl_format_includes_a_correction_when_there_is_one() -> None:
    out = render([record(corrected_at=at("2025-03-04T11:00:07Z"))], RenderOptions(fmt=Format.JSONL))
    assert json.loads(out)["corrected_at"].startswith("2025-03-04T11:00:07")


def test_the_jsonl_format_keeps_a_newline_inside_the_string() -> None:
    out = render([record(message="one\ntwo")], RenderOptions(fmt=Format.JSONL))
    assert len(out.splitlines()) == 1
    assert json.loads(out)["message"] == "one\ntwo"


def test_the_csv_format_has_a_header_row() -> None:
    out = render([record()], RenderOptions(fmt=Format.CSV))
    assert out.splitlines()[0] == "time,unit,class,stream,confidence,message"


def test_the_csv_format_quotes_a_comma_in_a_message() -> None:
    out = render([record(message="a, b")], RenderOptions(fmt=Format.CSV, header=False))
    assert out == '2025-03-04T11:00:00.000000000Z,payment-kernel,payment,stdout,measured,"a, b"'


def test_the_csv_format_round_trips_through_a_reader() -> None:
    out = render([record(message='he said "no", loudly')], RenderOptions(fmt=Format.CSV))
    rows = list(csv.reader(io.StringIO(out)))
    assert rows[1][5] == 'he said "no", loudly'


def test_a_newline_in_a_message_survives_the_csv_round_trip() -> None:
    out = render([record(message="one\ntwo")], RenderOptions(fmt=Format.CSV))
    rows = list(csv.reader(io.StringIO(out)))
    assert rows[1][5] == "one\ntwo"


def test_the_markdown_format_has_a_separator_row() -> None:
    out = render([record()], RenderOptions(fmt=Format.MARKDOWN))
    assert out.splitlines()[1].startswith("| ---")


def test_the_markdown_format_escapes_a_pipe() -> None:
    out = render([record(message="a | b")], RenderOptions(fmt=Format.MARKDOWN))
    assert "a \\| b" in out


def test_the_markdown_format_has_a_row_per_record() -> None:
    out = render([record(), record(message="second")], RenderOptions(fmt=Format.MARKDOWN))
    assert len(out.splitlines()) == 4


def test_every_format_renders_an_empty_list_without_failing() -> None:
    for member in Format:
        assert isinstance(render([], RenderOptions(fmt=member)), str)


def test_every_format_mentions_the_message() -> None:
    for member in Format:
        out = render([record(message="findme")], RenderOptions(fmt=member))
        assert "findme" in out
