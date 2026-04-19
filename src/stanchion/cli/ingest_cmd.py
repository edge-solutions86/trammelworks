"""The commands a field engineer runs to see what ingest is doing.

``discover`` answers "is the box even looking at the right files", which is the
first question on every support call and the one people waste an hour on. It
prints what is followed and what was skipped, with the reason, because "we are
not reading anything" and "we are reading nothing because it is all rotated" get
different remedies.

``tail`` runs the real pipeline for a bounded number of passes and prints the
records. It exists so that an engineer can prove the chain works end to end
without waiting for a shipment or touching the spool.
"""

from __future__ import annotations

import click

from stanchion.cli.main import Context, cli, fail, reporting
from stanchion.clocks import SystemClock
from stanchion.ingest.engine import IngestEngine
from stanchion.records import LogRecord

DEFAULT_PASSES = 1
MAX_MESSAGE_PREVIEW = 160


def render(record: LogRecord, full: bool) -> str:
    """One line per record, the way a person reads it."""
    body = record.message if full else record.message[:MAX_MESSAGE_PREVIEW]
    body = body.replace("\n", "\\n")
    return f"{record.observed_at.isoformat()} {record.unit} {record.stream.value} {body}"


@cli.command("discover")
@click.option("--skipped/--no-skipped", default=True, help="Also list what was left alone.")
@click.pass_obj
def discover_command(obj: Context, skipped: bool) -> None:
    """List the container log files this site would follow."""
    with reporting():
        config = obj.load()
        engine = IngestEngine(config=config, clock=SystemClock())
        found = engine.refresh()
    click.echo(f"log root {config.ingest.log_root}")
    for info in found.follow:
        detection = engine.detections.get(info.path)
        shape = detection.format.value if detection is not None else "unknown"
        click.echo(f"follow  {info.unit:<20} {shape:<9} {info.path}")
    if not found.follow:
        click.echo("follow  nothing")
    if not skipped:
        return
    for reason, count in sorted(found.reasons().items()):
        click.echo(f"skipped {reason.value:<20} {count}")


@cli.command("tail")
@click.option("--passes", default=DEFAULT_PASSES, show_default=True, help="How many polls to run.")
@click.option("--full/--truncate", default=False, help="Print whole messages.")
@click.option("--unit", "units", multiple=True, help="Only these units.")
@click.pass_obj
def tail_command(obj: Context, passes: int, full: bool, units: tuple[str, ...]) -> None:
    """Run the ingest pipeline for a few passes and print what comes out."""
    if passes < 1:
        fail("passes must be at least 1")
    with reporting():
        config = obj.load()
        engine = IngestEngine(config=config, clock=SystemClock())
        engine.refresh()
        wanted = set(units)
        total = 0
        for _ in range(passes):
            for record in engine.poll():
                if wanted and record.unit not in wanted:
                    continue
                total += 1
                click.echo(render(record, full))
        for record in engine.close():
            if wanted and record.unit not in wanted:
                continue
            total += 1
            click.echo(render(record, full))
    if obj.verbose:
        click.echo(f"{total} records from {len(engine.detections)} files", err=True)
