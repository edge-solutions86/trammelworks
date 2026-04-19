"""``stanchion run`` and ``stanchion ship`` — the two ways the box is driven.

``run`` is what the unit file starts: collect, ship, wait, repeat. It is bounded
by ``--cycles`` rather than looping forever by default, because the same command
is how an engineer runs one pass by hand and watches what happens, and a command
that only ever runs forever cannot be used that way.

``ship`` does the uplink half on its own. That separation exists for the visit
where a modem has been swapped: the spool already holds two days of records,
nothing new needs collecting, and the engineer wants to watch the backlog drain
before driving away.

Neither of them sleeps between cycles unless asked to. A sleep inside a command
someone is watching is the difference between a tool and a wait.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

import click

from stanchion.cli.main import Context, cli, fail, reporting
from stanchion.site import CycleReport, Site

DEFAULT_CYCLES = 1


@dataclass(slots=True)
class RunTotals:
    """Everything several cycles did."""

    cycles: int = 0
    read: int = 0
    spooled: int = 0
    redacted: int = 0
    sent: int = 0
    bytes_sent: int = 0
    stopped: str = ""

    def absorb(self, report: CycleReport) -> None:
        self.cycles += 1
        self.read += report.collect.read
        self.spooled += report.collect.shipped_to_spool
        self.redacted += report.collect.redacted
        self.sent += report.ship.records_sent
        self.bytes_sent += report.ship.bytes_sent
        if report.ship.stopped:
            self.stopped = report.ship.stopped

    def __str__(self) -> str:
        return (
            f"{self.cycles} cycles, read {self.read}, spooled {self.spooled}, "
            f"redacted {self.redacted}, sent {self.sent} in {self.bytes_sent} bytes"
        )


@cli.command("run")
@click.option("--cycles", default=DEFAULT_CYCLES, show_default=True, help="How many passes.")
@click.option("--forever", is_flag=True, help="Keep going until interrupted.")
@click.option("--interval", default=None, type=float, help="Seconds to wait between passes.")
@click.pass_obj
def run_command(obj: Context, cycles: int, forever: bool, interval: float | None) -> None:
    """Collect from the container logs and ship, once or repeatedly."""
    if cycles < 1:
        fail("cycles must be at least 1")
    with reporting():
        config = obj.load()
        site = Site.build(config=config)
        site.open()
    wait = interval if interval is not None else config.ship.interval.total_seconds()
    totals = RunTotals()
    try:
        _drive(site, totals, cycles, forever, wait, obj.verbose)
    except KeyboardInterrupt:
        click.echo("interrupted", err=True)
    finally:
        left = site.close()
        if left.read and obj.verbose:
            click.echo(f"flushed {left.read} records on shutdown", err=True)
    click.echo(str(totals))


def _drive(
    site: Site, totals: RunTotals, cycles: int, forever: bool, wait: float, verbose: bool
) -> None:
    done = 0
    while forever or done < cycles:
        report = site.cycle()
        totals.absorb(report)
        done += 1
        if verbose:
            click.echo(
                f"cycle {done}: read {report.collect.read}, sent {report.ship.records_sent}",
                err=True,
            )
        if report.ship.wait_seconds:
            click.echo(f"link is unhappy, waiting {report.ship.wait_seconds:.1f}s", err=True)
        if (forever or done < cycles) and wait > 0:
            time.sleep(max(wait, report.ship.wait_seconds))


@cli.command("ship")
@click.option("--passes", default=1, show_default=True, help="How many uplink passes.")
@click.pass_obj
def ship_command(obj: Context, passes: int) -> None:
    """Drain the spool to the collector without collecting anything new."""
    if passes < 1:
        fail("passes must be at least 1")
    with reporting():
        site = Site.build(config=obj.load())
        site.open()
    total_records = 0
    total_bytes = 0
    for _ in range(passes):
        report = site.ship()
        total_records += report.records_sent
        total_bytes += report.bytes_sent
        if report.blocked_by_meter:
            click.echo(f"held back by the meter: {len(report.blocked_by_meter)} segments", err=True)
        if report.stopped:
            click.echo(f"stopped: {report.stopped}", err=True)
            break
        if report.idle:
            break
    click.echo(f"sent {total_records} records in {total_bytes} bytes")
    click.echo(site.shipper.meter.describe())
