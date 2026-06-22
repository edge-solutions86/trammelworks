from __future__ import annotations

import json
from pathlib import Path

import pytest
from click.testing import CliRunner, Result

from stanchion.cli.main import EXIT_OK, cli
from stanchion.clocks import ManualClock, SystemClock
from stanchion.errors import ShipError
from stanchion.replay import (
    ReplayReport,
    check_one,
    find_batches,
    read_payload,
    records_of,
    replay,
)
from stanchion.ship.batch import Batch
from stanchion.ship.signing import MIN_SECRET_BYTES, Signer, SigningKey
from stanchion.ship.transport import FileTransport
from stanchion.timestamps import NanoTime

SECRET = b"k" * MIN_SECRET_BYTES
NOW = NanoTime.parse("2025-04-11T08:30:00Z")
SITE = "US-TX-0417"


@pytest.fixture
def key() -> SigningKey:
    return SigningKey(key_id="tx-0417-2025", secret=SECRET)


@pytest.fixture
def stick(tmp_path: Path) -> Path:
    made = tmp_path / "stick"
    made.mkdir()
    return made


def signer_for(key: SigningKey) -> Signer:
    clock = ManualClock()
    clock.set_wall("2025-04-11T08:30:00Z")
    return Signer(key=key, clock=clock)


def batch(index: int, count: int = 2, site: str = SITE) -> Batch:
    return Batch(
        site=site,
        records=tuple({"message": f"line {index}-{item}"} for item in range(count)),
        source=f"seg-{index}",
    )


def write_batches(
    stick: Path, key: SigningKey | None, howmany: int = 3, now: bool = False
) -> list[Batch]:
    """Write some batches, signed at the fixed test instant or at this one.

    The command line uses the real clock, and the signature window is ten days
    wide, so a batch signed at the fixed test instant is correctly refused by
    it. The CLI tests sign at the current instant for that reason.
    """
    signer = None
    if key is not None:
        signer = Signer(key=key, clock=SystemClock()) if now else signer_for(key)
    transport = FileTransport(directory=stick, signer=signer)
    made = [batch(index) for index in range(howmany)]
    for item in made:
        transport.send(item)
    return made


def test_an_empty_directory_has_no_batches(stick: Path) -> None:
    assert find_batches(stick) == []


def test_a_directory_that_is_not_one_is_refused(tmp_path: Path) -> None:
    target = tmp_path / "file.json"
    target.write_text("{}", encoding="utf-8")
    with pytest.raises(ShipError, match="not a directory"):
        find_batches(target)


def test_batches_are_found_with_their_signatures(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 2)
    found = find_batches(stick)
    assert len(found) == 2
    assert all(item.signed for item in found)


def test_an_unsigned_batch_is_found_without_one(stick: Path) -> None:
    write_batches(stick, None, 2)
    found = find_batches(stick)
    assert len(found) == 2
    assert not any(item.signed for item in found)


def test_files_that_are_not_batches_are_ignored(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 1)
    (stick / "notes.txt").write_text("hello", encoding="utf-8")
    (stick / "inner").mkdir()
    assert len(find_batches(stick)) == 1


def test_a_payload_reads_back(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 1)
    payload = read_payload(find_batches(stick)[0].path)
    assert payload["site"] == SITE
    assert len(payload["records"]) == 2


def test_a_file_that_is_not_json_is_refused(stick: Path) -> None:
    target = stick / "broken.json"
    target.write_text("not json at all", encoding="utf-8")
    with pytest.raises(ShipError, match="not json"):
        read_payload(target)


def test_a_json_file_that_is_not_an_object_is_refused(stick: Path) -> None:
    target = stick / "list.json"
    target.write_text("[1, 2, 3]", encoding="utf-8")
    with pytest.raises(ShipError, match="not an object"):
        read_payload(target)


@pytest.mark.parametrize("missing", ["site", "batch_id", "records"])
def test_a_batch_missing_a_field_is_refused(stick: Path, missing: str) -> None:
    payload = {"site": SITE, "batch_id": "abcd", "records": []}
    del payload[missing]
    target = stick / "short.json"
    target.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(ShipError, match=f"no {missing}"):
        read_payload(target)


def test_records_that_are_not_a_list_are_refused(stick: Path) -> None:
    target = stick / "odd.json"
    target.write_text(json.dumps({"site": SITE, "batch_id": "a", "records": {}}), encoding="utf-8")
    with pytest.raises(ShipError, match="not a list"):
        read_payload(target)


def test_an_honest_batch_verifies(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 1)
    verdict = check_one(find_batches(stick)[0], key, NOW)
    assert verdict.ok
    assert verdict.records == 2
    assert verdict.site == SITE


def test_a_verdict_reads_as_one_line(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 1)
    assert "ok, 2 records" in str(check_one(find_batches(stick)[0], key, NOW))


def test_a_tampered_batch_does_not_verify(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 1)
    found = find_batches(stick)[0]
    payload = read_payload(found.path)
    payload["records"][0]["message"] = "something else entirely"
    found.path.write_text(
        json.dumps(payload, separators=(",", ":"), sort_keys=True), encoding="utf-8"
    )
    verdict = check_one(found, key, NOW)
    assert not verdict
    assert "does not match" in verdict.reason


def test_a_batch_signed_by_another_key_does_not_verify(stick: Path, key: SigningKey) -> None:
    other = SigningKey(key_id="tx-9999-2025", secret=b"z" * MIN_SECRET_BYTES)
    write_batches(stick, other, 1)
    verdict = check_one(find_batches(stick)[0], key, NOW)
    assert not verdict
    assert "another key" in verdict.reason


def test_a_batch_with_no_signature_is_refused(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 1)
    found = find_batches(stick)[0]
    assert found.signature_path is not None
    found.signature_path.unlink()
    verdict = check_one(find_batches(stick)[0], key, NOW)
    assert not verdict
    assert "no signature" in verdict.reason


def test_a_signature_that_is_not_one_is_refused(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 1)
    found = find_batches(stick)[0]
    assert found.signature_path is not None
    found.signature_path.write_text("who knows", encoding="utf-8")
    assert not check_one(find_batches(stick)[0], key, NOW)


def test_without_a_key_nothing_is_taken_as_verified(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 1)
    verdict = check_one(find_batches(stick)[0], None, NOW)
    assert not verdict
    assert "no key" in verdict.reason


def test_unsigned_batches_can_be_accepted_deliberately(stick: Path) -> None:
    write_batches(stick, None, 1)
    verdict = check_one(find_batches(stick)[0], None, NOW, require_signature=False)
    assert verdict.ok
    assert verdict.records == 2


def test_a_whole_stick_verifies(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 4)
    report = replay(stick, key, NOW)
    assert report.clean
    assert len(report.accepted) == 4
    assert report.records == 8


def test_the_report_names_the_sites(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 2)
    assert replay(stick, key, NOW).sites == (SITE,)


def test_the_report_reads_as_one_line(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 2)
    assert "2 batches accepted, 4 records from 1 sites" in str(replay(stick, key, NOW))


def test_one_bad_file_does_not_stop_the_rest(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 3)
    (stick / "broken.json").write_text("not json", encoding="utf-8")
    report = replay(stick, key, NOW)
    assert len(report.accepted) == 3
    assert len(report.refused) == 1
    assert not report.clean


def test_the_verdicts_come_back_in_a_stable_order(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 5)
    first = [item.batch_id for item in replay(stick, key, NOW).verdicts]
    second = [item.batch_id for item in replay(stick, key, NOW).verdicts]
    assert first == second == sorted(first)


def test_an_empty_report_is_not_clean() -> None:
    assert ReplayReport().clean is False


def test_the_records_can_be_pulled_out(stick: Path, key: SigningKey) -> None:
    write_batches(stick, key, 3)
    paths = [item.path for item in replay(stick, key, NOW).accepted]
    assert len(records_of(paths)) == 6


def test_pulling_records_keeps_duplicates(stick: Path, key: SigningKey) -> None:
    # Delivery is at least once and deduplicating is the collector's job.
    write_batches(stick, key, 1)
    paths = [item.path for item in replay(stick, key, NOW).accepted]
    assert len(records_of(paths + paths)) == 4


def invoke(*args: str) -> Result:
    return CliRunner().invoke(cli, ["verify", *args])


@pytest.fixture
def key_file(tmp_path: Path) -> Path:
    target = tmp_path / "site.key"
    target.write_bytes(SECRET)
    return target


def test_the_command_verifies_a_stick(stick: Path, key: SigningKey, key_file: Path) -> None:
    write_batches(stick, key, 2, now=True)
    result = invoke(str(stick), "--key", str(key_file), "--key-id", "tx-0417-2025")
    assert result.exit_code == EXIT_OK
    assert "2 batches accepted" in result.output


def test_the_command_refuses_a_tampered_stick(stick: Path, key: SigningKey, key_file: Path) -> None:
    write_batches(stick, key, 2, now=True)
    found = find_batches(stick)[0]
    found.path.write_text('{"site": "x", "batch_id": "y", "records": []}', encoding="utf-8")
    result = invoke(str(stick), "--key", str(key_file), "--key-id", "tx-0417-2025")
    assert result.exit_code != EXIT_OK
    assert "REFUSED" in result.output


def test_a_key_without_an_id_is_refused(stick: Path, key_file: Path) -> None:
    result = invoke(str(stick), "--key", str(key_file))
    assert "needs its key id" in result.output


def test_no_key_and_no_flag_is_refused(stick: Path) -> None:
    assert "or --unsigned" in invoke(str(stick)).output


def test_the_command_says_when_nothing_was_verified(stick: Path) -> None:
    write_batches(stick, None, 1)
    result = invoke(str(stick), "--unsigned")
    assert "nothing was verified" in result.output


def test_an_empty_stick_says_so(stick: Path) -> None:
    assert "no batch files" in invoke(str(stick), "--unsigned").output


def test_quiet_prints_only_the_refused(stick: Path, key: SigningKey, key_file: Path) -> None:
    write_batches(stick, key, 2, now=True)
    (stick / "broken.json").write_text("not json", encoding="utf-8")
    result = invoke(str(stick), "--key", str(key_file), "--key-id", "tx-0417-2025", "--quiet")
    assert "broken.json" in result.output
    assert result.output.count("ok, 2 records") == 0
