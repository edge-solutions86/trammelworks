from __future__ import annotations

import json

import pytest

from stanchion.errors import ParseError
from stanchion.ingest.detect import Format, detect, first_line, sniff
from stanchion.paths import Layout

TS = "2025-03-04T11:30:00.123456789Z"
CID = "3f" * 32
POD_DIR = "/var/log/pods/forecourt_pump-bridge-0_1f2e3d4c-aaaa-bbbb-cccc-1234567890ab"
CRI_PATH = f"{POD_DIR}/pump-bridge/0.log"
DOCKER_PATH = f"/var/lib/docker/containers/{CID}/{CID}-json.log"

CRI_SAMPLE = f"{TS} stdout F auth approved\n".encode()
JSON_SAMPLE = (
    json.dumps({"log": "auth approved\n", "stream": "stdout", "time": TS}) + "\n"
).encode()
JOURNAL_SAMPLE = b"__REALTIME_TIMESTAMP=1741087800123456\nMESSAGE=auth approved\n\n"


def test_first_line_stops_at_the_newline() -> None:
    assert first_line(b"one\ntwo\n") == b"one"


def test_first_line_of_an_unterminated_sample_is_the_whole_thing() -> None:
    assert first_line(b"only") == b"only"


def test_sniff_finds_cri() -> None:
    assert sniff(CRI_SAMPLE) is Format.CRI


def test_sniff_finds_cri_without_a_fraction() -> None:
    assert sniff(b"2025-03-04T11:30:00Z stderr P chunk\n") is Format.CRI


def test_sniff_finds_cri_with_an_offset_timestamp() -> None:
    assert sniff(b"2025-03-04T05:30:00.1-06:00 stdout F x\n") is Format.CRI


def test_sniff_finds_jsonfile() -> None:
    assert sniff(JSON_SAMPLE) is Format.JSONFILE


def test_sniff_finds_journal() -> None:
    assert sniff(JOURNAL_SAMPLE) is Format.JOURNAL


def test_sniff_returns_none_for_an_empty_sample() -> None:
    assert sniff(b"") is None
    assert sniff(b"\n\n") is None


def test_sniff_returns_none_for_prose() -> None:
    assert sniff(b"something went wrong at 11:30\n") is None


def test_json_that_is_not_a_log_record_is_not_jsonfile() -> None:
    assert sniff(b'{"hello": "world"}\n') is None


def test_broken_json_is_not_jsonfile() -> None:
    assert sniff(b'{"log": "x", "time":\n') is None


def test_a_json_array_is_not_jsonfile() -> None:
    assert sniff(b'[{"log": "x", "time": "y"}]\n') is None


def test_detect_reads_a_cri_file_at_a_cri_path() -> None:
    found = detect(CRI_PATH, CRI_SAMPLE)
    assert found.format is Format.CRI
    assert found.from_content is True
    assert found.path_layout is Layout.CRI
    assert found.disagreed is False


def test_detect_reads_a_json_file_at_a_docker_path() -> None:
    found = detect(DOCKER_PATH, JSON_SAMPLE)
    assert found.format is Format.JSONFILE
    assert found.disagreed is False


def test_content_wins_when_a_migrated_box_left_cri_in_a_docker_tree() -> None:
    found = detect(DOCKER_PATH, CRI_SAMPLE)
    assert found.format is Format.CRI
    assert found.from_content is True
    assert found.disagreed is True


def test_the_path_decides_an_empty_file() -> None:
    found = detect(CRI_PATH, b"")
    assert found.format is Format.CRI
    assert found.from_content is False


def test_an_empty_file_at_a_docker_path_is_jsonfile() -> None:
    assert detect(DOCKER_PATH, b"").format is Format.JSONFILE


def test_an_empty_file_never_counts_as_a_disagreement() -> None:
    assert detect(DOCKER_PATH, b"").disagreed is False


def test_a_flat_symlink_path_expects_cri() -> None:
    path = f"/var/log/containers/pump-bridge-0_forecourt_pump-bridge-{CID}.log"
    assert detect(path, b"").format is Format.CRI


def test_an_unknown_path_with_unreadable_content_is_refused() -> None:
    with pytest.raises(ParseError, match="cannot tell what format"):
        detect("/var/log/messages", b"just some prose\n")


def test_the_refusal_names_the_path() -> None:
    with pytest.raises(ParseError) as caught:
        detect("/var/log/messages", b"prose\n")
    assert caught.value.source == "/var/log/messages"


def test_an_unknown_path_with_readable_content_is_fine() -> None:
    found = detect("/var/log/messages", CRI_SAMPLE)
    assert found.format is Format.CRI
    assert found.path_layout is None
    assert found.disagreed is False
