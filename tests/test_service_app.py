from __future__ import annotations

import json
from pathlib import Path

import pytest

from stanchion.archive.config import ArchiveSettings
from stanchion.archive.repository import Repository
from stanchion.archive.schema import open_archive
from stanchion.config import SiteConfig
from stanchion.records import LogRecord, Stream
from stanchion.service.app import MAX_LIMIT, Request, Service
from stanchion.spool.config import SpoolSettings
from stanchion.timestamps import NanoTime

NOW = NanoTime.parse("2025-03-04T12:00:00Z")

CORPUS = [
    ("payment-kernel", "11:00:00", "auth approved"),
    ("payment-kernel", "11:15:00", "auth declined"),
    ("pump-bridge", "11:30:00", "nozzle 1 lifted"),
]


@pytest.fixture
def archive_file(tmp_path: Path) -> Path:
    return tmp_path / "archive" / "records.sqlite3"


@pytest.fixture
def config(tmp_path: Path, archive_file: Path) -> SiteConfig:
    return SiteConfig(
        site="US-TX-0417",
        spool=SpoolSettings(directory=(tmp_path / "spool").as_posix()),
        archive=ArchiveSettings(path=archive_file.as_posix()),
    )


@pytest.fixture
def filled(archive_file: Path) -> None:
    repo = Repository(connection=open_archive(archive_file))
    for unit, when, message in CORPUS:
        repo.add(
            LogRecord(
                site="US-TX-0417",
                unit=unit,
                stream=Stream.STDOUT,
                observed_at=NanoTime.parse(f"2025-03-04T{when}Z"),
                message=message,
            ),
            NOW,
        )
    repo.connection.close()


def service(config: SiteConfig, token: str = "") -> Service:
    return Service(config=config, now=NOW, token=token)


def get(config: SiteConfig, target: str, **kwargs: object):  # type: ignore[no-untyped-def]
    return service(config).handle(Request.of(target, **kwargs))  # type: ignore[arg-type]


def test_a_target_is_split_into_path_and_query() -> None:
    request = Request.of("/records?filter=unit+%3D+a&limit=5")
    assert request.path == "/records"
    assert request.one("limit") == "5"
    assert request.one("filter") == "unit = a"


def test_a_trailing_slash_is_ignored() -> None:
    assert Request.of("/health/").path == "/health"


def test_the_root_path_survives_the_trim() -> None:
    assert Request.of("/").path == "/"


def test_a_missing_parameter_falls_back() -> None:
    assert Request.of("/records").one("limit", "7") == "7"


def test_a_bearer_token_is_read() -> None:
    request = Request.of("/", headers={"Authorization": "Bearer abc"})
    assert request.token == "abc"


def test_a_header_name_is_matched_without_case() -> None:
    request = Request.of("/", headers={"AUTHORIZATION": "Bearer abc"})
    assert request.token == "abc"


def test_a_non_bearer_header_gives_no_token() -> None:
    assert Request.of("/", headers={"Authorization": "Basic abc"}).token == ""


def test_health_reports_the_site(config: SiteConfig) -> None:
    found = get(config, "/health")
    assert found.status == 200
    assert found.payload["site"] == "US-TX-0417"
    assert found.payload["status"] == "ok"


def test_the_root_is_the_health_route(config: SiteConfig) -> None:
    assert get(config, "/").payload["status"] == "ok"


def test_an_unknown_route_is_a_404(config: SiteConfig) -> None:
    found = get(config, "/admin")
    assert found.status == 404
    assert "/health" in found.payload["routes"]


def test_a_write_method_is_refused(config: SiteConfig) -> None:
    found = service(config).handle(Request.of("/health", method="POST"))
    assert found.status == 405
    assert "only reads" in found.payload["error"]


def test_a_token_is_required_when_one_is_set(config: SiteConfig) -> None:
    found = service(config, token="secret").handle(Request.of("/health"))
    assert found.status == 401


def test_the_right_token_is_accepted(config: SiteConfig) -> None:
    request = Request.of("/health", headers={"Authorization": "Bearer secret"})
    assert service(config, token="secret").handle(request).status == 200


def test_a_wrong_token_is_refused(config: SiteConfig) -> None:
    request = Request.of("/health", headers={"Authorization": "Bearer wrong"})
    assert service(config, token="secret").handle(request).status == 401


def test_no_token_configured_means_no_check(config: SiteConfig) -> None:
    assert get(config, "/health").status == 200


def test_the_summary_route_reports(config: SiteConfig, filled: None) -> None:
    found = get(config, "/summary")
    assert found.status == 200
    assert found.payload["site"] == "US-TX-0417"
    assert found.payload["archived"] == 3


def test_the_summary_carries_the_problems(config: SiteConfig, filled: None) -> None:
    # A site with no log root is following nothing, which the summary flags.
    assert get(config, "/summary").payload["problems"]


def test_records_come_back_as_json(config: SiteConfig, filled: None) -> None:
    found = get(config, "/records")
    assert found.status == 200
    assert found.payload["count"] == 3
    assert found.payload["records"][0]["message"] == "auth approved"


def test_records_can_be_filtered(config: SiteConfig, filled: None) -> None:
    found = get(config, "/records?filter=unit+%3D+pump-bridge")
    assert found.payload["count"] == 1


def test_a_broken_filter_is_a_400(config: SiteConfig, filled: None) -> None:
    found = get(config, "/records?filter=unti+%3D+a")
    assert found.status == 400
    assert found.payload["code"] == "stanchion.query"


def test_the_limit_is_honoured(config: SiteConfig, filled: None) -> None:
    assert get(config, "/records?limit=1").payload["count"] == 1


def test_the_limit_is_capped(config: SiteConfig, filled: None) -> None:
    found = get(config, f"/records?limit={MAX_LIMIT * 10}")
    assert found.status == 200
    assert found.payload["count"] == 3


def test_a_limit_of_zero_is_refused(config: SiteConfig, filled: None) -> None:
    assert get(config, "/records?limit=0").status == 400


def test_a_non_numeric_limit_is_refused(config: SiteConfig, filled: None) -> None:
    found = get(config, "/records?limit=lots")
    assert found.status == 400
    assert "not a number" in found.payload["error"]


def test_records_before_the_archive_exists_are_a_404(config: SiteConfig) -> None:
    assert get(config, "/records").status == 404


def test_a_site_with_no_archive_gets_a_409(tmp_path: Path) -> None:
    config = SiteConfig(
        site="US-TX-0417",
        spool=SpoolSettings(directory=(tmp_path / "spool").as_posix()),
        archive=ArchiveSettings(enabled=False),
    )
    assert get(config, "/records").status == 409


def test_the_units_route_lists_units(config: SiteConfig, filled: None) -> None:
    found = get(config, "/units")
    assert found.payload["units"] == ["payment-kernel", "pump-bridge"]


def test_the_units_route_is_empty_without_an_archive(config: SiteConfig) -> None:
    assert get(config, "/units").payload["units"] == []


def test_every_response_is_json(config: SiteConfig, filled: None) -> None:
    for target in ("/health", "/summary", "/records", "/units", "/nope"):
        body = get(config, target).body()
        assert isinstance(json.loads(body), dict)


def test_an_error_response_is_json_too(config: SiteConfig) -> None:
    payload = json.loads(get(config, "/admin").body())
    assert "error" in payload
