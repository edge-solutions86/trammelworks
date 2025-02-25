from __future__ import annotations

import pytest

from stanchion.errors import ParseError
from stanchion.paths import Layout, parse_log_path, rotation_order

CID = "3f" * 32
POD_DIR = "/var/log/pods/forecourt_pump-bridge-0_1f2e3d4c-aaaa-bbbb-cccc-1234567890ab"
CRI_PATH = f"{POD_DIR}/pump-bridge/0.log"
FLAT_PATH = f"/var/log/containers/pump-bridge-0_forecourt_pump-bridge-{CID}.log"
DOCKER_PATH = f"/var/lib/docker/containers/{CID}/{CID}-json.log"


def test_cri_path_gives_unit_and_attempt() -> None:
    info = parse_log_path(CRI_PATH)
    assert info.layout is Layout.CRI
    assert info.unit == "pump-bridge"
    assert info.attempt == 0
    assert info.namespace == "forecourt"
    assert info.pod == "pump-bridge-0"


def test_cri_attempt_follows_the_restart_count() -> None:
    assert parse_log_path(f"{POD_DIR}/pump-bridge/14.log").attempt == 14


def test_cri_path_without_a_pod_directory_is_refused() -> None:
    with pytest.raises(ParseError, match="no pod directory"):
        parse_log_path("/tmp/pump-bridge/0.log")


def test_cri_rotation_is_read_from_the_suffix() -> None:
    assert parse_log_path(f"{POD_DIR}/pump-bridge/0.log.3").rotation == 3


def test_cri_compressed_rotation_is_recognised() -> None:
    info = parse_log_path(f"{POD_DIR}/pump-bridge/0.log.3.gz")
    assert info.compressed is True
    assert info.rotation == 3
    assert info.attempt == 0


def test_flat_path_gives_unit_and_container_id() -> None:
    info = parse_log_path(FLAT_PATH)
    assert info.layout is Layout.FLAT
    assert info.unit == "pump-bridge"
    assert info.container_id == CID
    assert info.pod == "pump-bridge-0"
    assert info.namespace == "forecourt"


def test_flat_path_has_no_attempt() -> None:
    assert parse_log_path(FLAT_PATH).attempt is None


def test_flat_path_container_name_may_contain_dashes() -> None:
    path = f"/var/log/containers/x_forecourt_tank-gauge-poller-{CID}.log"
    assert parse_log_path(path).unit == "tank-gauge-poller"


def test_flat_path_needs_a_full_length_id() -> None:
    with pytest.raises(ParseError, match="unrecognised"):
        parse_log_path("/var/log/containers/p_ns_pump-bridge-3f3f3f.log")


def test_docker_path_names_the_unit_after_the_short_id() -> None:
    info = parse_log_path(DOCKER_PATH)
    assert info.layout is Layout.DOCKER
    assert info.container_id == CID
    assert info.unit == "c3f3f3f3f3f3f"


def test_docker_path_must_sit_in_its_own_directory() -> None:
    with pytest.raises(ParseError, match="own container directory"):
        parse_log_path(f"/var/lib/docker/containers/other/{CID}-json.log")


def test_docker_rotation_is_read() -> None:
    info = parse_log_path(f"/var/lib/docker/containers/{CID}/{CID}-json.log.2")
    assert info.rotation == 2
    assert info.is_current is False


@pytest.mark.parametrize(
    "path",
    [
        "/var/log/messages",
        "/var/log/containers/nothing.log",
        "/var/log/pods/ns_pod_uid/container/notanumber.log",
        "",
        "/var/lib/docker/containers/abc/abc-json.txt",
    ],
)
def test_unrecognised_paths_are_refused(path: str) -> None:
    with pytest.raises(ParseError):
        parse_log_path(path)


def test_the_error_names_the_path_it_looked_at() -> None:
    with pytest.raises(ParseError) as caught:
        parse_log_path("/var/log/messages")
    assert caught.value.source == "/var/log/messages"


def test_is_current_is_true_only_for_the_live_file() -> None:
    assert parse_log_path(CRI_PATH).is_current is True
    assert parse_log_path(f"{POD_DIR}/pump-bridge/0.log.1").is_current is False
    assert parse_log_path(f"{POD_DIR}/pump-bridge/0.log.gz").is_current is False


def test_rotation_order_reads_oldest_first() -> None:
    files = [
        parse_log_path(f"{POD_DIR}/pump-bridge/0.log"),
        parse_log_path(f"{POD_DIR}/pump-bridge/0.log.1"),
        parse_log_path(f"{POD_DIR}/pump-bridge/0.log.2"),
    ]
    ordered = sorted(files, key=rotation_order)
    assert [f.rotation for f in ordered] == [2, 1, 0]


def test_rotation_order_puts_a_compressed_file_before_its_plain_twin() -> None:
    plain = parse_log_path(f"{POD_DIR}/pump-bridge/0.log.1")
    packed = parse_log_path(f"{POD_DIR}/pump-bridge/0.log.1.gz")
    assert sorted([plain, packed], key=rotation_order) == [packed, plain]


def test_zstd_is_also_treated_as_compressed() -> None:
    assert parse_log_path(f"{POD_DIR}/pump-bridge/0.log.1.zst").compressed is True


def test_parse_accepts_a_purepath() -> None:
    from pathlib import PurePosixPath

    assert parse_log_path(PurePosixPath(CRI_PATH)).unit == "pump-bridge"
