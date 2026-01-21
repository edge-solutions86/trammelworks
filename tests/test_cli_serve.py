from __future__ import annotations

import json
import threading
import urllib.error
import urllib.request
from pathlib import Path

import pytest
from click.testing import CliRunner, Result

from stanchion.cli.main import EXIT_OK, cli


@pytest.fixture
def config(tmp_path: Path) -> Path:
    target = tmp_path / "site.yaml"
    target.write_text(
        "site: US-TX-0417\n"
        "spool:\n"
        f"  directory: {(tmp_path / 'spool').as_posix()}\n"
        "archive:\n"
        f"  path: {(tmp_path / 'archive' / 'records.sqlite3').as_posix()}\n",
        encoding="utf-8",
    )
    return target


def invoke(config: Path, *args: str) -> Result:
    return CliRunner().invoke(cli, ["--config", str(config), "serve", *args])


def test_binding_off_loopback_is_refused_without_permission(config: Path) -> None:
    result = invoke(config, "--host", "0.0.0.0", "--token", "x", "--once")
    assert result.exit_code != EXIT_OK
    assert "allow_any_interface" in result.output


def test_binding_off_loopback_is_refused_without_a_token(config: Path) -> None:
    result = invoke(config, "--host", "0.0.0.0", "--any-interface", "--once")
    assert result.exit_code != EXIT_OK
    assert "needs a token" in result.output


def test_a_bad_port_is_refused(config: Path) -> None:
    result = invoke(config, "--port", "70000", "--once")
    assert result.exit_code != EXIT_OK
    assert "port is out of range" in result.output


def test_a_missing_config_is_refused(tmp_path: Path) -> None:
    result = CliRunner().invoke(cli, ["--config", str(tmp_path / "nope.yaml"), "serve", "--once"])
    assert "stanchion.config" in result.output


def _serve_once(config: Path, port: int, box: dict[str, Result]) -> None:
    box["result"] = invoke(config, "--port", str(port), "--once")


def _free_port() -> int:
    import socket

    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def test_one_request_is_served_and_then_it_stops(config: Path) -> None:
    port = _free_port()
    box: dict[str, Result] = {}
    thread = threading.Thread(target=_serve_once, args=(config, port, box), daemon=True)
    thread.start()
    payload = None
    for _ in range(50):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=5) as answer:
                payload = json.loads(answer.read())
            break
        except (urllib.error.URLError, ConnectionError):
            continue
    thread.join(timeout=10)
    assert payload is not None
    assert payload["site"] == "US-TX-0417"
    assert box["result"].exit_code == EXIT_OK


def test_the_startup_line_names_the_address(config: Path) -> None:
    port = _free_port()
    box: dict[str, Result] = {}
    thread = threading.Thread(target=_serve_once, args=(config, port, box), daemon=True)
    thread.start()
    for _ in range(50):
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=5).read()
            break
        except (urllib.error.URLError, ConnectionError):
            continue
    thread.join(timeout=10)
    assert f"http://127.0.0.1:{port}" in box["result"].output
    assert "nothing off this box can reach it" in box["result"].output
