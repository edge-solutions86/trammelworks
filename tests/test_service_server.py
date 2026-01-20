from __future__ import annotations

import json
import threading
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from stanchion.archive.config import ArchiveSettings
from stanchion.clocks import ManualClock
from stanchion.config import SiteConfig
from stanchion.errors import StanchionError
from stanchion.eventlog import EventLog, Level
from stanchion.service.app import Request
from stanchion.service.server import ServerSettings, ServiceHost
from stanchion.spool.config import SpoolSettings
from stanchion.telemetry import Registry


@pytest.fixture
def config(tmp_path: Path) -> SiteConfig:
    return SiteConfig(
        site="US-TX-0417",
        spool=SpoolSettings(directory=(tmp_path / "spool").as_posix()),
        archive=ArchiveSettings(path=(tmp_path / "archive" / "records.sqlite3").as_posix()),
    )


def host(config: SiteConfig, **over: object) -> ServiceHost:
    clock = ManualClock()
    clock.set_wall("2025-03-04T12:00:00Z")
    settings = ServerSettings(host="127.0.0.1", port=0, **over)  # type: ignore[arg-type]
    return ServiceHost(config=config, settings=settings, clock=clock, metrics=Registry())


def test_the_default_settings_are_loopback() -> None:
    assert ServerSettings().loopback_only is True


def test_a_port_out_of_range_is_refused() -> None:
    with pytest.raises(StanchionError, match="port is out of range"):
        ServerSettings(port=70000)


def test_a_negative_port_is_refused() -> None:
    with pytest.raises(StanchionError, match="port is out of range"):
        ServerSettings(port=-1)


def test_port_zero_means_ask_the_kernel() -> None:
    assert ServerSettings(port=0).port == 0


def test_binding_off_loopback_needs_permission() -> None:
    with pytest.raises(StanchionError, match="allow_any_interface"):
        ServerSettings(host="0.0.0.0", token="x")


def test_binding_off_loopback_also_needs_a_token() -> None:
    with pytest.raises(StanchionError, match="needs a token"):
        ServerSettings(host="0.0.0.0", allow_any_interface=True)


def test_binding_off_loopback_with_both_is_allowed() -> None:
    built = ServerSettings(host="0.0.0.0", allow_any_interface=True, token="secret")
    assert built.loopback_only is False


def test_dispatch_answers_health(config: SiteConfig) -> None:
    found = host(config).dispatch(Request.of("/health"))
    assert found.status == 200
    assert found.payload["site"] == "US-TX-0417"


def test_dispatch_counts_requests(config: SiteConfig) -> None:
    built = host(config)
    built.dispatch(Request.of("/health"))
    built.dispatch(Request.of("/nope"))
    snapshot = built.metrics.snapshot()
    assert snapshot["service_requests"] == 2
    assert snapshot["service_refusals"] == 1


def test_dispatch_logs_the_status(config: SiteConfig) -> None:
    clock = ManualClock()
    events = EventLog(minimum=Level.DEBUG, clock=clock)
    built = ServiceHost(config=config, settings=ServerSettings(port=0), events=events, clock=clock)
    built.dispatch(Request.of("/health"))
    record = events.records()[0]
    assert record["event"] == "service_request"
    assert record["status"] == 200


def test_the_token_reaches_the_service(config: SiteConfig) -> None:
    built = host(config, token="secret")
    assert built.dispatch(Request.of("/health")).status == 401


def test_the_address_before_binding_is_what_was_asked_for(config: SiteConfig) -> None:
    built = ServiceHost(config=config, settings=ServerSettings(port=9999))
    assert built.address == ("127.0.0.1", 9999)


def test_building_binds_a_real_port(config: SiteConfig) -> None:
    built = host(config)
    built.build()
    try:
        assert built.address[1] > 0
    finally:
        built.close()


def test_closing_twice_is_harmless(config: SiteConfig) -> None:
    built = host(config)
    built.build()
    built.close()
    built.close()


def running(built: ServiceHost) -> threading.Thread:
    server = built.build()
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return thread


def fetch(built: ServiceHost, path: str, token: str = "") -> tuple[int, dict]:  # type: ignore[type-arg]
    address = built.address
    request = urllib.request.Request(f"http://{address[0]}:{address[1]}{path}")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=5) as answer:
            return answer.status, json.loads(answer.read())
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read())


def test_a_real_request_gets_a_real_answer(config: SiteConfig) -> None:
    built = host(config)
    thread = running(built)
    try:
        status, payload = fetch(built, "/health")
        assert status == 200
        assert payload["site"] == "US-TX-0417"
    finally:
        if built.server is not None:
            built.server.shutdown()
        built.close()
        thread.join(timeout=5)


def test_a_real_request_for_an_unknown_route_is_a_404(config: SiteConfig) -> None:
    built = host(config)
    thread = running(built)
    try:
        status, payload = fetch(built, "/admin")
        assert status == 404
        assert "error" in payload
    finally:
        if built.server is not None:
            built.server.shutdown()
        built.close()
        thread.join(timeout=5)


def test_a_real_request_without_a_token_is_refused(config: SiteConfig) -> None:
    built = host(config, token="secret")
    thread = running(built)
    try:
        assert fetch(built, "/health")[0] == 401
        assert fetch(built, "/health", token="secret")[0] == 200
    finally:
        if built.server is not None:
            built.server.shutdown()
        built.close()
        thread.join(timeout=5)


def test_a_post_is_refused_over_the_wire(config: SiteConfig) -> None:
    built = host(config)
    thread = running(built)
    try:
        address = built.address
        request = urllib.request.Request(
            f"http://{address[0]}:{address[1]}/health", data=b"{}", method="POST"
        )
        try:
            with urllib.request.urlopen(request, timeout=5) as answer:
                status = answer.status
        except urllib.error.HTTPError as exc:
            status = exc.code
        assert status == 405
    finally:
        if built.server is not None:
            built.server.shutdown()
        built.close()
        thread.join(timeout=5)
