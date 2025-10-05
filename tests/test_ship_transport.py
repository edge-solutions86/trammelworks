from __future__ import annotations

import io
import json
import urllib.error
from pathlib import Path
from typing import Any

import pytest

from stanchion.errors import ShipError
from stanchion.ship.backoff import Outcome
from stanchion.ship.batch import Batch
from stanchion.ship.transport import (
    FileTransport,
    HttpTransport,
    MemoryTransport,
    NullTransport,
    Response,
    decode_body,
    outcome_for,
    parse_retry_after,
)

SITE = "US-TX-0417"


def batch(n: int = 1) -> Batch:
    return Batch(site=SITE, records=tuple({"n": index} for index in range(n)))


@pytest.mark.parametrize("status", [200, 201, 202, 204])
def test_a_two_hundred_is_ok(status: int) -> None:
    assert outcome_for(status) is Outcome.OK


@pytest.mark.parametrize("status", [408, 425, 500, 502, 503, 504, 599])
def test_a_transient_status_is_retryable(status: int) -> None:
    assert outcome_for(status) is Outcome.RETRYABLE


def test_a_four_two_nine_is_throttled() -> None:
    assert outcome_for(429) is Outcome.THROTTLED


@pytest.mark.parametrize("status", [400, 401, 403, 404, 409, 413, 422])
def test_a_client_error_is_terminal(status: int) -> None:
    assert outcome_for(status) is Outcome.TERMINAL


def test_a_retry_after_in_seconds_is_read() -> None:
    assert parse_retry_after("30") == 30.0
    assert parse_retry_after(" 2.5 ") == 2.5


def test_an_absent_or_unreadable_retry_after_is_none() -> None:
    assert parse_retry_after(None) is None
    assert parse_retry_after("Wed, 21 Oct 2026 07:28:00 GMT") is None
    assert parse_retry_after("-5") is None


def test_the_memory_transport_keeps_what_it_was_given() -> None:
    transport = MemoryTransport()
    transport.send(batch(2))
    assert len(transport.received) == 1
    assert transport.received[0].count == 2


def test_the_memory_transport_defaults_to_accepting() -> None:
    assert MemoryTransport().send(batch()).ok is True


def test_the_memory_transport_follows_its_script() -> None:
    transport = MemoryTransport(
        script=[Response(outcome=Outcome.RETRYABLE, status=503), Response(Outcome.OK, 200)]
    )
    assert transport.send(batch()).outcome is Outcome.RETRYABLE
    assert transport.send(batch()).outcome is Outcome.OK


def test_the_memory_transport_falls_back_to_its_default_when_the_script_runs_out() -> None:
    transport = MemoryTransport(script=[Response(Outcome.RETRYABLE, 503)])
    transport.send(batch())
    assert transport.send(batch()).ok is True


def test_the_memory_transport_reports_the_size_it_saw() -> None:
    sent = batch(3)
    assert MemoryTransport().send(sent).bytes_sent == sent.wire_bytes


def test_the_memory_transport_lists_the_batch_ids() -> None:
    transport = MemoryTransport()
    first = batch(1)
    transport.send(first)
    assert transport.batch_ids == [first.batch_id]


def test_the_null_transport_accepts_and_counts() -> None:
    transport = NullTransport()
    transport.send(batch())
    transport.send(batch(2))
    assert transport.sent == 2
    assert transport.bytes_sent > 0


def test_the_file_transport_writes_a_file_per_batch(tmp_path: Path) -> None:
    transport = FileTransport(directory=tmp_path / "outbox")
    sent = batch(2)
    result = transport.send(sent)
    assert result.ok is True
    assert (tmp_path / "outbox" / f"{sent.batch_id}.json").exists()


def test_the_written_file_is_the_batch_payload(tmp_path: Path) -> None:
    transport = FileTransport(directory=tmp_path / "outbox")
    sent = batch(2)
    transport.send(sent)
    payload = json.loads((tmp_path / "outbox" / f"{sent.batch_id}.json").read_bytes())
    assert payload["count"] == 2
    assert payload["site"] == SITE


def test_the_file_transport_creates_its_directory(tmp_path: Path) -> None:
    FileTransport(directory=tmp_path / "deep" / "outbox").send(batch())
    assert (tmp_path / "deep" / "outbox").is_dir()


def test_writing_the_same_batch_twice_is_one_file(tmp_path: Path) -> None:
    transport = FileTransport(directory=tmp_path / "outbox")
    sent = batch(2)
    transport.send(sent)
    transport.send(sent)
    assert len(list((tmp_path / "outbox").iterdir())) == 1


def test_a_file_transport_that_cannot_write_is_retryable(tmp_path: Path) -> None:
    blocker = tmp_path / "outbox"
    blocker.write_text("not a directory", encoding="utf-8")
    result = FileTransport(directory=blocker).send(batch())
    assert result.outcome is Outcome.RETRYABLE


def test_an_http_transport_needs_an_http_url() -> None:
    with pytest.raises(ShipError, match="must be an http url"):
        HttpTransport(endpoint="collector.invalid")


def test_an_http_transport_needs_a_positive_timeout() -> None:
    with pytest.raises(ShipError, match="timeout must be positive"):
        HttpTransport(endpoint="https://c.invalid/v1", timeout=0)


class FakeAnswer:
    def __init__(self, status: int) -> None:
        self.status = status

    def __enter__(self) -> FakeAnswer:
        return self

    def __exit__(self, *args: Any) -> None:
        return None


def test_a_successful_post_is_ok() -> None:
    seen: dict[str, Any] = {}

    def opener(request: Any, timeout: float) -> FakeAnswer:
        seen["url"] = request.full_url
        seen["body"] = request.data
        seen["timeout"] = timeout
        return FakeAnswer(200)

    transport = HttpTransport(endpoint="https://c.invalid/v1", opener=opener)
    result = transport.send(batch(2))
    assert result.ok is True
    assert seen["url"] == "https://c.invalid/v1"
    assert json.loads(seen["body"])["count"] == 2


def test_the_token_becomes_a_bearer_header() -> None:
    seen: dict[str, Any] = {}

    def opener(request: Any, timeout: float) -> FakeAnswer:
        seen["auth"] = request.get_header("Authorization")
        return FakeAnswer(200)

    HttpTransport(endpoint="https://c.invalid/v1", token="abc", opener=opener).send(batch())
    assert seen["auth"] == "Bearer abc"


def test_no_token_means_no_auth_header() -> None:
    seen: dict[str, Any] = {}

    def opener(request: Any, timeout: float) -> FakeAnswer:
        seen["auth"] = request.get_header("Authorization")
        return FakeAnswer(200)

    HttpTransport(endpoint="https://c.invalid/v1", opener=opener).send(batch())
    assert seen["auth"] is None


def test_an_http_error_is_mapped_and_carries_its_hint() -> None:
    def opener(request: Any, timeout: float) -> FakeAnswer:
        raise urllib.error.HTTPError(
            url="https://c.invalid/v1",
            code=429,
            msg="slow down",
            hdrs={"Retry-After": "17"},  # type: ignore[arg-type]
            fp=io.BytesIO(b""),
        )

    result = HttpTransport(endpoint="https://c.invalid/v1", opener=opener).send(batch())
    assert result.outcome is Outcome.THROTTLED
    assert result.retry_after == 17.0
    assert result.status == 429


def test_a_refused_connection_is_retryable() -> None:
    def opener(request: Any, timeout: float) -> FakeAnswer:
        raise urllib.error.URLError("connection refused")

    result = HttpTransport(endpoint="https://c.invalid/v1", opener=opener).send(batch())
    assert result.outcome is Outcome.RETRYABLE
    assert "refused" in result.message


def test_a_timeout_is_retryable() -> None:
    def opener(request: Any, timeout: float) -> FakeAnswer:
        raise TimeoutError("timed out")

    assert (
        HttpTransport(endpoint="https://c.invalid/v1", opener=opener).send(batch()).outcome
        is Outcome.RETRYABLE
    )


def test_a_bad_request_is_terminal() -> None:
    def opener(request: Any, timeout: float) -> FakeAnswer:
        raise urllib.error.HTTPError(
            url="https://c.invalid/v1",
            code=400,
            msg="no",
            hdrs={},  # type: ignore[arg-type]
            fp=io.BytesIO(b""),
        )

    assert (
        HttpTransport(endpoint="https://c.invalid/v1", opener=opener).send(batch()).outcome
        is Outcome.TERMINAL
    )


def test_the_bytes_sent_are_reported_even_on_a_failure() -> None:
    def opener(request: Any, timeout: float) -> FakeAnswer:
        raise urllib.error.URLError("gone")

    sent = batch(3)
    result = HttpTransport(endpoint="https://c.invalid/v1", opener=opener).send(sent)
    assert result.bytes_sent == sent.wire_bytes


def test_decode_body_reads_a_json_object() -> None:
    assert decode_body(b'{"accepted": 4}') == {"accepted": 4}


def test_decode_body_of_junk_is_empty() -> None:
    assert decode_body(b"<html>gateway timeout</html>") == {}
    assert decode_body(b"[1,2]") == {}
