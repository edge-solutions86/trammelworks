"""Writing a forecourt's worth of container logs, on demand.

Every test in this repository that needs traffic has been building it by hand,
one line at a time, which is fine for a unit test and useless for the questions
that only appear at volume: does the spool roll, does eviction pick the right
class, does a shift's worth of records come out of the other end in order.

So this writes a corpus. It is deliberately *not* random noise. A forecourt has
a shape and the shape is what makes the corpus worth having:

* the tank gauge is the loudest thing on the box and says almost nothing
* the payment kernel is quiet and every line of it matters
* the pump bridge writes stack traces when a dispenser resets, several lines
  each, and occasionally a raw RS-485 frame that is not valid UTF-8
* the price sign speaks twice a day
* chronyd corrects the clock when the modem comes up

Seeded, so a failure is reproducible. An unseeded generator finds a bug once and
then cannot be asked about it again.
"""

from __future__ import annotations

import json
import random
from dataclasses import dataclass, field
from pathlib import Path

from stanchion.timestamps import NS_PER_SECOND, NanoTime

CRI_CHUNK = 16 * 1024

#: Roughly how often each unit says anything, relative to the others.
WEIGHTS: dict[str, int] = {
    "tank-gauge": 50,
    "pump-bridge": 25,
    "payment-kernel": 12,
    "loyalty-sync": 8,
    "price-sign": 3,
    "chronyd": 2,
}

TANK_LINES = (
    "tank {tank} level {level} ml temperature {temp}C",
    "tank {tank} water 0 ml",
    "probe {tank} ok",
)

PUMP_LINES = (
    "nozzle {pump} lifted",
    "nozzle {pump} replaced, {litres} litres",
    "dispenser {pump} authorised",
    "dispenser {pump} reset requested",
)

PAYMENT_LINES = (
    "auth request pan={pan} amount={amount}",
    "auth approved ref {ref}",
    "auth declined ref {ref} reason 51",
    "settlement batch {ref} closed",
)

LOYALTY_LINES = (
    "sync started",
    "sync sent {count} events",
    "loyalty={loyalty} points {points}",
)

TRACE = (
    "java.lang.IllegalStateException: dispenser {pump} did not answer",
    "    at Pump.reset(Pump.java:41)",
    "    at Bridge.poll(Bridge.java:118)",
    "    at Main.run(Main.java:9)",
)


@dataclass(slots=True)
class Simulation:
    """A seeded generator for one site's traffic."""

    seed: int = 41
    start: str = "2025-03-04T06:00:00Z"
    gap_seconds: float = 4.0
    lossy_every: int = 400
    trace_every: int = 60
    rng: random.Random = field(init=False)
    at: NanoTime = field(init=False)
    written: int = field(default=0, init=False)

    def __post_init__(self) -> None:
        self.rng = random.Random(self.seed)
        self.at = NanoTime.parse(self.start)

    def _tick(self) -> NanoTime:
        step = self.rng.expovariate(1.0 / self.gap_seconds)
        self.at = self.at.shifted(int(step * NS_PER_SECOND))
        return self.at

    def _unit(self) -> str:
        names = list(WEIGHTS)
        return self.rng.choices(names, weights=[WEIGHTS[name] for name in names], k=1)[0]

    def _pan(self) -> str:
        body = "4" + "".join(str(self.rng.randint(0, 9)) for _ in range(14))
        from stanchion.redact.luhn import luhn_check_digit

        return body + luhn_check_digit(body)

    def message_for(self, unit: str) -> list[str]:
        """One event from a unit, which may be several lines."""
        if unit == "tank-gauge":
            return [
                self.rng.choice(TANK_LINES).format(
                    tank=self.rng.randint(1, 4),
                    level=self.rng.randint(4000, 42000),
                    temp=self.rng.randint(4, 28),
                )
            ]
        if unit == "pump-bridge":
            if self.written % self.trace_every == self.trace_every - 1:
                return [line.format(pump=self.rng.randint(1, 8)) for line in TRACE]
            return [
                self.rng.choice(PUMP_LINES).format(
                    pump=self.rng.randint(1, 8), litres=self.rng.randint(5, 70)
                )
            ]
        if unit == "payment-kernel":
            return [
                self.rng.choice(PAYMENT_LINES).format(
                    pan=self._pan(),
                    amount=self.rng.randint(500, 12000),
                    ref=self.rng.randint(100000, 999999),
                )
            ]
        if unit == "loyalty-sync":
            return [
                self.rng.choice(LOYALTY_LINES).format(
                    count=self.rng.randint(1, 40),
                    loyalty=self.rng.randint(10**10, 10**11 - 1),
                    points=self.rng.randint(0, 4000),
                )
            ]
        if unit == "price-sign":
            return [f"price unleaded {self.rng.randint(1200, 1800)}"]
        return [f"System clock wrong by -{self.rng.random():.6f} seconds, adjustment started"]

    def cri_lines(self, count: int) -> dict[str, list[str]]:
        """Generate CRI lines, grouped by unit."""
        out: dict[str, list[str]] = {}
        for _ in range(count):
            unit = self._unit()
            stamp = self._tick()
            stream = "stderr" if unit == "pump-bridge" and self.rng.random() < 0.1 else "stdout"
            for message in self.message_for(unit):
                out.setdefault(unit, []).extend(
                    _split_cri(stamp.isoformat(), stream, message, CRI_CHUNK)
                )
            self.written += 1
        return out

    def write_cri(self, root: Path, count: int, namespace: str = "forecourt") -> dict[str, Path]:
        """Write a corpus into a CRI shaped log root."""
        grouped = self.cri_lines(count)
        paths: dict[str, Path] = {}
        for unit, lines in grouped.items():
            folder = root / "pods" / f"{namespace}_{unit}-0_{'1f2e3d4c-' + 'a' * 4}" / unit
            folder.mkdir(parents=True, exist_ok=True)
            target = folder / "0.log"
            with target.open("a", encoding="utf-8", newline="") as handle:
                handle.write("".join(lines))
            paths[unit] = target
        return paths

    def write_jsonfile(self, target: Path, count: int) -> Path:
        """Write a corpus in the docker json-file shape."""
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("a", encoding="utf-8", newline="") as handle:
            for _ in range(count):
                unit = self._unit()
                stamp = self._tick()
                for message in self.message_for(unit):
                    handle.write(
                        json.dumps(
                            {
                                "log": message + "\n",
                                "stream": "stdout",
                                "time": stamp.isoformat(),
                            }
                        )
                        + "\n"
                    )
                self.written += 1
        return target


def _split_cri(stamp: str, stream: str, message: str, chunk: int) -> list[str]:
    """One application line as the runtime would write it, split if long."""
    if len(message) <= chunk:
        return [f"{stamp} {stream} F {message}\n"]
    out = []
    while len(message) > chunk:
        out.append(f"{stamp} {stream} P {message[:chunk]}\n")
        message = message[chunk:]
    out.append(f"{stamp} {stream} F {message}\n")
    return out
