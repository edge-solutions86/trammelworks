from __future__ import annotations

from pathlib import Path

import pytest

from stanchion.clocks import ManualClock
from stanchion.errors import ConfigError, ShipError
from stanchion.ship.batch import Batch
from stanchion.ship.config import ShipSettings
from stanchion.ship.signing import (
    DEFAULT_SKEW_SECONDS,
    MIN_SECRET_BYTES,
    SIGNATURE_VERSION,
    Signature,
    Signer,
    SigningKey,
    parse_signature,
    read_key,
    sign,
    signing_string,
    verify,
)
from stanchion.ship.transport import (
    SIGNATURE_HEADER,
    SIGNATURE_SUFFIX,
    FileTransport,
    HttpTransport,
)
from stanchion.timestamps import NanoTime

SECRET = b"k" * MIN_SECRET_BYTES
NOW = NanoTime.parse("2025-04-11T08:30:00Z")
SITE = "US-TX-0417"


@pytest.fixture
def key() -> SigningKey:
    return SigningKey(key_id="tx-0417-2025", secret=SECRET)


@pytest.fixture
def batch() -> Batch:
    return Batch(site=SITE, records=({"message": "nozzle 1 lifted"},), source="seg-1")


def signed(key: SigningKey, batch: Batch, at: NanoTime = NOW) -> Signature:
    return sign(key, batch.site, batch.batch_id, batch.encode(), at)


def test_a_key_id_with_spaces_is_refused() -> None:
    with pytest.raises(ShipError, match="malformed signing key id"):
        SigningKey(key_id="tx 0417", secret=SECRET)


def test_an_empty_key_id_is_refused() -> None:
    with pytest.raises(ShipError, match="malformed signing key id"):
        SigningKey(key_id="", secret=SECRET)


def test_a_short_secret_is_refused() -> None:
    with pytest.raises(ShipError, match="too short"):
        SigningKey(key_id="tx-0417", secret=b"short")


def test_an_enormous_secret_is_refused() -> None:
    with pytest.raises(ShipError, match="implausibly long"):
        SigningKey(key_id="tx-0417", secret=b"k" * 5000)


def test_the_secret_never_appears_in_the_repr(key: SigningKey) -> None:
    assert "kkkk" not in repr(key)
    assert "32 bytes" in repr(key)


def test_a_key_is_read_off_disk(tmp_path: Path) -> None:
    path = tmp_path / "site.key"
    path.write_bytes(SECRET)
    assert read_key(path, "tx-0417").secret == SECRET


def test_a_trailing_newline_is_not_part_of_the_key(tmp_path: Path) -> None:
    path = tmp_path / "site.key"
    path.write_bytes(SECRET + b"\n")
    assert read_key(path, "tx-0417").secret == SECRET


def test_an_empty_key_file_is_refused(tmp_path: Path) -> None:
    path = tmp_path / "site.key"
    path.write_bytes(b"   \n")
    with pytest.raises(ShipError, match="empty"):
        read_key(path, "tx-0417")


def test_a_missing_key_file_is_refused(tmp_path: Path) -> None:
    with pytest.raises(ShipError, match="unreadable"):
        read_key(tmp_path / "absent.key", "tx-0417")


def test_the_signed_string_carries_every_field() -> None:
    material = signing_string("tx-0417", "2025-04-11T08:30:00Z", SITE, "abcd", b"{}")
    assert material.startswith(SIGNATURE_VERSION.encode())
    for part in (b"tx-0417", b"2025-04-11T08:30:00Z", SITE.encode(), b"abcd", b"{}"):
        assert part in material


def test_a_newline_in_a_signed_field_is_refused() -> None:
    with pytest.raises(ShipError, match="cannot contain a newline"):
        signing_string("tx-0417", "2025\n04", SITE, "abcd", b"{}")


def test_the_same_batch_signs_the_same_way(key: SigningKey, batch: Batch) -> None:
    assert signed(key, batch).digest == signed(key, batch).digest


def test_a_different_key_signs_differently(batch: Batch) -> None:
    other = SigningKey(key_id="tx-0417-2025", secret=b"z" * MIN_SECRET_BYTES)
    assert (
        signed(SigningKey(key_id="tx-0417-2025", secret=SECRET), batch).digest
        != signed(other, batch).digest
    )


def test_a_different_time_signs_differently(key: SigningKey, batch: Batch) -> None:
    later = NanoTime(NOW.epoch_ns + 1_000_000_000)
    assert signed(key, batch).digest != signed(key, batch, later).digest


def test_a_signature_reads_back_from_its_header(key: SigningKey, batch: Batch) -> None:
    original = signed(key, batch)
    assert parse_signature(original.header()) == original


def test_a_header_that_is_not_one_is_refused() -> None:
    with pytest.raises(ShipError, match="malformed"):
        parse_signature("who knows")


def test_a_header_with_a_short_digest_is_refused(key: SigningKey, batch: Batch) -> None:
    header = signed(key, batch).header()[:-4]
    with pytest.raises(ShipError, match="malformed"):
        parse_signature(header)


def test_an_honest_signature_verifies(key: SigningKey, batch: Batch) -> None:
    check = verify(key, signed(key, batch), batch.site, batch.batch_id, batch.encode(), NOW)
    assert check.ok
    assert check.reason == ""


def test_a_verified_check_is_truthy(key: SigningKey, batch: Batch) -> None:
    assert verify(key, signed(key, batch), batch.site, batch.batch_id, batch.encode(), NOW)


def test_a_changed_body_does_not_verify(key: SigningKey, batch: Batch) -> None:
    check = verify(key, signed(key, batch), batch.site, batch.batch_id, b"{}", NOW)
    assert check.reason == "signature does not match"


def test_a_batch_replayed_as_another_site_does_not_verify(key: SigningKey, batch: Batch) -> None:
    check = verify(key, signed(key, batch), "US-TX-9999", batch.batch_id, batch.encode(), NOW)
    assert not check


def test_a_batch_replayed_under_another_id_does_not_verify(key: SigningKey, batch: Batch) -> None:
    check = verify(key, signed(key, batch), batch.site, "beefbeef", batch.encode(), NOW)
    assert not check


def test_another_key_is_named_not_just_refused(key: SigningKey, batch: Batch) -> None:
    other = SigningKey(key_id="tx-9999-2025", secret=SECRET)
    check = verify(key, signed(other, batch), batch.site, batch.batch_id, batch.encode(), NOW)
    assert "another key" in check.reason


def test_another_version_is_refused(key: SigningKey, batch: Batch) -> None:
    stale = Signature(
        key_id=key.key_id,
        timestamp="2025-04-11T08:30:00Z",
        digest="0" * 64,
        version="stanchion-batch-v0",
    )
    check = verify(key, stale, batch.site, batch.batch_id, batch.encode(), NOW)
    assert "another version" in check.reason


def test_an_unreadable_timestamp_is_refused(key: SigningKey, batch: Batch) -> None:
    broken = Signature(key_id=key.key_id, timestamp="last tuesday", digest="0" * 64)
    check = verify(key, broken, batch.site, batch.batch_id, batch.encode(), NOW)
    assert "unreadable" in check.reason


def test_a_signature_from_long_ago_is_refused(key: SigningKey, batch: Batch) -> None:
    old = NanoTime(NOW.epoch_ns - (DEFAULT_SKEW_SECONDS + 60) * 1_000_000_000)
    check = verify(key, signed(key, batch, old), batch.site, batch.batch_id, batch.encode(), NOW)
    assert "seconds away from now" in check.reason


def test_a_signature_inside_the_window_still_verifies(key: SigningKey, batch: Batch) -> None:
    old = NanoTime(NOW.epoch_ns - (DEFAULT_SKEW_SECONDS - 60) * 1_000_000_000)
    assert verify(key, signed(key, batch, old), batch.site, batch.batch_id, batch.encode(), NOW)


def test_a_window_can_be_narrowed(key: SigningKey, batch: Batch) -> None:
    old = NanoTime(NOW.epoch_ns - 3_600_000_000_000)
    check = verify(
        key,
        signed(key, batch, old),
        batch.site,
        batch.batch_id,
        batch.encode(),
        NOW,
        skew_seconds=60,
    )
    assert not check


def signer_for(key: SigningKey) -> Signer:
    clock = ManualClock()
    clock.set_wall("2025-04-11T08:30:00Z")
    return Signer(key=key, clock=clock)


def test_a_signer_counts_what_it_signed(key: SigningKey, batch: Batch) -> None:
    signer = signer_for(key)
    signer.for_batch(batch, batch.encode())
    signer.for_batch(batch, batch.encode())
    assert signer.signed == 2


def test_a_signer_produces_a_header_that_verifies(key: SigningKey, batch: Batch) -> None:
    header = signer_for(key).header_for(batch, batch.encode())
    found = parse_signature(header)
    assert verify(key, found, batch.site, batch.batch_id, batch.encode(), NOW)


def test_a_file_transport_writes_the_signature_beside_the_batch(
    tmp_path: Path, key: SigningKey, batch: Batch
) -> None:
    transport = FileTransport(directory=tmp_path, signer=signer_for(key))
    transport.send(batch)
    beside = tmp_path / f"{batch.batch_id}.json{SIGNATURE_SUFFIX}"
    assert beside.exists()


def test_the_signature_beside_the_batch_verifies(
    tmp_path: Path, key: SigningKey, batch: Batch
) -> None:
    transport = FileTransport(directory=tmp_path, signer=signer_for(key))
    transport.send(batch)
    body = (tmp_path / f"{batch.batch_id}.json").read_bytes()
    header = (tmp_path / f"{batch.batch_id}.json{SIGNATURE_SUFFIX}").read_text(encoding="utf-8")
    assert verify(key, parse_signature(header), batch.site, batch.batch_id, body, NOW)


def test_an_unsigned_file_transport_writes_no_sidecar(tmp_path: Path, batch: Batch) -> None:
    FileTransport(directory=tmp_path).send(batch)
    assert not list(tmp_path.glob("*" + SIGNATURE_SUFFIX))


class Recorder:
    def __init__(self) -> None:
        self.headers: dict[str, str] = {}

    def __call__(self, request: object, timeout: float = 0.0) -> Recorder:
        self.headers = dict(getattr(request, "headers", {}))
        return self

    def __enter__(self) -> Recorder:
        return self

    def __exit__(self, *args: object) -> None:
        return None

    status = 200


def test_an_http_transport_sends_the_signature_header(key: SigningKey, batch: Batch) -> None:
    recorder = Recorder()
    transport = HttpTransport(
        endpoint="https://collector.invalid/v1", signer=signer_for(key), opener=recorder
    )
    transport.send(batch)
    assert any(name.lower() == SIGNATURE_HEADER.lower() for name in recorder.headers)


def test_the_header_it_sends_verifies(key: SigningKey, batch: Batch) -> None:
    recorder = Recorder()
    HttpTransport(
        endpoint="https://collector.invalid/v1", signer=signer_for(key), opener=recorder
    ).send(batch)
    header = next(value for name, value in recorder.headers.items() if "signature" in name.lower())
    assert verify(key, parse_signature(header), batch.site, batch.batch_id, batch.encode(), NOW)


def test_an_unsigned_http_transport_sends_no_such_header(batch: Batch) -> None:
    recorder = Recorder()
    HttpTransport(endpoint="https://collector.invalid/v1", opener=recorder).send(batch)
    assert not any("signature" in name.lower() for name in recorder.headers)


def test_a_key_file_without_an_id_is_refused() -> None:
    with pytest.raises(ConfigError, match="both a key file and a key id"):
        ShipSettings(signing_key_file="/etc/stanchion/site.key")


def test_a_key_id_without_a_file_is_refused() -> None:
    with pytest.raises(ConfigError, match="both a key file and a key id"):
        ShipSettings(signing_key_id="tx-0417")


def test_a_site_without_signing_has_no_key() -> None:
    assert ShipSettings().signing_key() is None
    assert ShipSettings().signs is False


def test_a_configured_key_is_loaded(tmp_path: Path) -> None:
    path = tmp_path / "site.key"
    path.write_bytes(SECRET)
    settings = ShipSettings(signing_key_file=str(path), signing_key_id="tx-0417")
    key = settings.signing_key()
    assert key is not None
    assert key.key_id == "tx-0417"


def test_a_configured_key_that_is_not_there_is_a_config_error(tmp_path: Path) -> None:
    settings = ShipSettings(signing_key_file=str(tmp_path / "absent.key"), signing_key_id="tx-0417")
    with pytest.raises(ConfigError, match="unreadable"):
        settings.signing_key()
