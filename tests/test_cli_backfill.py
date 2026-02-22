from __future__ import annotations

import gzip
import json
from pathlib import Path

import pytest
from click.testing import CliRunner, Result

from stanchion.cli.main import EXIT_OK, cli
from tests.support.logfiles import SiteTree, cri_full

TS = "2025-03-04T12:00:00.123456789Z"
VISA = "4111111111111111"


@pytest.fixture
def outbox(tmp_path: Path) -> Path:
    return tmp_path / "outbox"


@pytest.fixture
def config(tmp_path: Path, tree: SiteTree, outbox: Path) -> Path:
    tree.root.mkdir(parents=True, exist_ok=True)
    target = tmp_path / "site.yaml"
    target.write_text(
        "site: US-TX-0417\n"
        "ingest:\n"
        f"  log_root: {tree.root.as_posix()}\n"
        "spool:\n"
        f"  directory: {(tmp_path / 'spool').as_posix()}\n"
        "  budget: 1MiB\n"
        "  segment: 16KiB\n"
        "archive:\n"
        f"  path: {(tmp_path / 'archive' / 'records.sqlite3').as_posix()}\n"
        "ship:\n"
        f"  endpoint: file://{outbox.as_posix()}\n",
        encoding="utf-8",
    )
    return target


def seed(tree: SiteTree, rotations: dict[str, str], compress: bool = False) -> Path:
    live = tree.write_cri("pump-bridge", [cri_full(TS, "the live line")])
    for suffix, text in rotations.items():
        target = live.with_name(live.name + suffix)
        if compress:
            target.write_bytes(gzip.compress(text.encode("utf-8")))
        else:
            target.write_text(text, encoding="utf-8", newline="")
    return live


def invoke(config: Path, *args: str) -> Result:
    return CliRunner().invoke(cli, ["--config", str(config), "backfill", *args])


def test_a_site_with_nothing_followed_says_so(config: Path) -> None:
    result = invoke(config)
    assert result.exit_code == EXIT_OK
    assert "nothing to backfill" in result.output


def test_a_dry_run_lists_the_rotated_files(config: Path, tree: SiteTree) -> None:
    seed(tree, {".1": cri_full(TS, "older"), ".2": cri_full(TS, "oldest")})
    result = invoke(config, "--dry-run")
    assert "pump-bridge" in result.output
    assert "2 rotated files" in result.output
    assert "would be read" in result.output


def test_a_dry_run_reads_nothing(config: Path, tree: SiteTree, tmp_path: Path) -> None:
    seed(tree, {".1": cri_full(TS, "older")})
    invoke(config, "--dry-run")
    assert not list((tmp_path / "spool").glob("*.spool"))


def test_a_backfill_puts_records_in_the_spool(config: Path, tree: SiteTree, tmp_path: Path) -> None:
    seed(tree, {".1": cri_full(TS, "older") + cri_full(TS, "older still")})
    result = invoke(config)
    assert "2 records backfilled" in result.output
    assert list((tmp_path / "spool").glob("*.spool"))


def test_a_backfill_reports_per_unit(config: Path, tree: SiteTree) -> None:
    seed(tree, {".1": cri_full(TS, "older")})
    assert "backfilled 1 records from 1 files" in invoke(config).output


def test_a_compressed_rotation_is_read(config: Path, tree: SiteTree) -> None:
    seed(tree, {".1.gz": cri_full(TS, "packed")}, compress=True)
    assert "1 records backfilled" in invoke(config).output


def test_a_backfilled_record_is_redacted(config: Path, tree: SiteTree, tmp_path: Path) -> None:
    seed(tree, {".1": cri_full(TS, f"pan={VISA}")})
    invoke(config)
    on_disk = b"".join(path.read_bytes() for path in (tmp_path / "spool").glob("*.spool"))
    assert VISA.encode() not in on_disk


def test_a_backfilled_record_reaches_the_archive(
    config: Path, tree: SiteTree, tmp_path: Path
) -> None:
    from stanchion.archive.repository import Repository
    from stanchion.archive.schema import open_archive

    seed(tree, {".1": cri_full(TS, "older")})
    invoke(config)
    repo = Repository(connection=open_archive(tmp_path / "archive" / "records.sqlite3"))
    assert repo.count() == 1


def test_the_unit_filter_narrows(config: Path, tree: SiteTree) -> None:
    seed(tree, {".1": cri_full(TS, "older")})
    tree.write_cri("tank-gauge", [cri_full(TS, "live gauge")])
    result = invoke(config, "--unit", "tank-gauge")
    assert "pump-bridge" not in result.output


def test_a_size_cap_can_be_given(config: Path, tree: SiteTree) -> None:
    seed(tree, {".1": cri_full(TS, "older")})
    assert invoke(config, "--max-bytes", "1KiB").exit_code == EXIT_OK


def test_a_file_over_the_cap_is_reported_not_read(config: Path, tree: SiteTree) -> None:
    seed(tree, {".1": cri_full(TS, "x" * 500)})
    result = invoke(config, "--max-bytes", "100B")
    assert "0 records backfilled" in result.output


def test_a_bad_size_is_refused(config: Path, tree: SiteTree) -> None:
    seed(tree, {".1": cri_full(TS, "older")})
    result = invoke(config, "--max-bytes", "loads")
    assert result.exit_code != EXIT_OK


def test_the_backfilled_records_can_then_be_shipped(
    config: Path, tree: SiteTree, outbox: Path
) -> None:
    seed(tree, {".1": cri_full(TS, "older")})
    invoke(config)
    CliRunner().invoke(cli, ["--config", str(config), "ship"])
    written = list(outbox.glob("*.json"))
    assert written
    payload = json.loads(written[0].read_text(encoding="utf-8"))
    assert any(item["message"] == "older" for item in payload["records"])


def test_a_missing_config_fails(tmp_path: Path) -> None:
    result = CliRunner().invoke(cli, ["--config", str(tmp_path / "nope.yaml"), "backfill"])
    assert "stanchion.config" in result.output
