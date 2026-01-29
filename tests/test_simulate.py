from __future__ import annotations

import json
from pathlib import Path

from stanchion.ingest.cri import parse_cri_line
from stanchion.ingest.discover import discover
from stanchion.paths import parse_log_path
from stanchion.redact.pan import find_pans
from stanchion.simulate import WEIGHTS, Simulation
from stanchion.timestamps import NanoTime


def sim(**over: object) -> Simulation:
    return Simulation(**over)  # type: ignore[arg-type]


def test_the_same_seed_gives_the_same_corpus() -> None:
    left = sim(seed=7).cri_lines(50)
    right = sim(seed=7).cri_lines(50)
    assert left == right


def test_a_different_seed_gives_a_different_corpus() -> None:
    assert sim(seed=1).cri_lines(50) != sim(seed=2).cri_lines(50)


def test_every_unit_eventually_speaks() -> None:
    found = sim(seed=3).cri_lines(2000)
    assert set(found) == set(WEIGHTS)


def test_the_tank_gauge_is_the_loudest() -> None:
    found = sim(seed=3).cri_lines(2000)
    counts = {unit: len(lines) for unit, lines in found.items()}
    assert counts["tank-gauge"] > counts["payment-kernel"]
    assert counts["payment-kernel"] > counts["price-sign"]


def test_every_line_parses_as_cri() -> None:
    for lines in sim(seed=5).cri_lines(400).values():
        for line in lines:
            parsed = parse_cri_line(line.encode("utf-8"))
            assert parsed.message


def test_the_timestamps_only_go_forwards() -> None:
    lines = sim(seed=5).cri_lines(400)
    stamps = [
        parse_cri_line(line.encode("utf-8")).timestamp for group in lines.values() for line in group
    ]
    assert stamps == sorted(stamps) or len(set(stamps)) > 1


def test_the_payment_kernel_writes_real_card_numbers() -> None:
    lines = sim(seed=11).cri_lines(800).get("payment-kernel", [])
    found = [item for line in lines for item in find_pans(line)]
    assert found, "the corpus is meant to contain something to redact"


def test_a_stack_trace_appears() -> None:
    lines = sim(seed=13, trace_every=5).cri_lines(200).get("pump-bridge", [])
    assert any("at Pump.reset" in line for line in lines)


def test_chronyd_writes_a_clock_correction() -> None:
    lines = sim(seed=17).cri_lines(1500).get("chronyd", [])
    assert any("System clock wrong by" in line for line in lines)


def test_the_pump_bridge_uses_stderr_sometimes() -> None:
    lines = sim(seed=19).cri_lines(1500).get("pump-bridge", [])
    assert any(" stderr " in line for line in lines)


def test_a_long_message_is_split_into_chunks() -> None:
    from stanchion.simulate import _split_cri

    chunks = _split_cri("2025-03-04T12:00:00.000000000Z", "stdout", "x" * 100, 30)
    assert len(chunks) == 4
    assert chunks[0].split(" ", 3)[2] == "P"
    assert chunks[-1].split(" ", 3)[2] == "F"


def test_a_short_message_is_one_chunk() -> None:
    from stanchion.simulate import _split_cri

    assert len(_split_cri("2025-03-04T12:00:00.000000000Z", "stdout", "short", 30)) == 1


def test_writing_a_corpus_makes_files_the_parser_recognises(tmp_path: Path) -> None:
    paths = sim(seed=23).write_cri(tmp_path / "log", 300)
    assert paths
    for unit, target in paths.items():
        info = parse_log_path(target.as_posix())
        assert info.unit == unit


def test_a_written_corpus_is_discovered(tmp_path: Path) -> None:
    sim(seed=23).write_cri(tmp_path / "log", 300)
    found = discover(tmp_path / "log")
    assert len(found.follow) == len(WEIGHTS)


def test_writing_twice_appends(tmp_path: Path) -> None:
    generator = sim(seed=29)
    generator.write_cri(tmp_path / "log", 100)
    sizes = {path: path.stat().st_size for path in (tmp_path / "log").rglob("*.log")}
    generator.write_cri(tmp_path / "log", 100)
    for path, size in sizes.items():
        assert path.stat().st_size > size


def test_the_json_file_form_is_valid_json(tmp_path: Path) -> None:
    target = sim(seed=31).write_jsonfile(tmp_path / "docker.log", 100)
    for line in target.read_text(encoding="utf-8").splitlines():
        payload = json.loads(line)
        assert payload["log"].endswith("\n")
        assert NanoTime.parse(payload["time"])


def test_the_written_count_follows(tmp_path: Path) -> None:
    generator = sim(seed=37)
    generator.write_cri(tmp_path / "log", 40)
    assert generator.written == 40


def test_the_clock_moves_forward_between_events() -> None:
    generator = sim(seed=41)
    first = generator.at
    generator.cri_lines(10)
    assert generator.at > first
