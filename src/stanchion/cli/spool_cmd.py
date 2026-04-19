"""``stanchion spool`` — what is on the disk and what would go first.

The question this answers on a support call is never "how full is it". It is
"if this site stays off the network for another two days, what do we lose". So
the output leads with the class breakdown and the eviction order, not with a
percentage.

``--plan`` asks the eviction planner what it would delete to free a given amount
and prints the answer without doing it. Being able to see that a site would have
to give up two days of tank gauge readings, and that it would still not be
enough, is what turns "the spool is nearly full" into a decision.
"""

from __future__ import annotations

import click

from stanchion.cli.main import Context, cli, loaded, reporting
from stanchion.spool.budget import measure, report
from stanchion.spool.evict import evictable_bytes, plan_eviction
from stanchion.spool.reader import read_segment
from stanchion.spool.segment import list_segments
from stanchion.units import format_bytes, parse_bytes


@cli.command("spool")
@click.option("--segments", is_flag=True, help="List every segment.")
@click.option("--plan", "plan_size", default=None, help="Show what freeing this much would cost.")
@click.option("--verify", is_flag=True, help="Read every segment and check its frames.")
@click.pass_obj
def spool_command(obj: Context, segments: bool, plan_size: str | None, verify: bool) -> None:
    """Report on the on disk spool."""
    config = loaded(obj)
    settings = config.spool
    table = settings.table()
    found = list_segments(settings.path)
    usage = measure(found)
    summary = report(settings.budget(), usage)
    click.echo(str(summary))
    click.echo(f"usable {format_bytes(settings.budget().usable_bytes)}")
    for klass in table.ship_order():
        held = usage.of_class(klass)
        mark = " protected" if klass in table.protected_classes else ""
        click.echo(f"  {klass:<12} {format_bytes(held):>10}{mark}")
    click.echo(f"evictable {format_bytes(evictable_bytes(found, table))}")
    if segments:
        _list_segments(found)
    if verify:
        _verify(found)
    if plan_size is not None:
        _plan(found, table, plan_size)


def _list_segments(found: list) -> None:  # type: ignore[type-arg]
    for item in found:
        click.echo(
            f"  {item.sequence:>6} {item.klass:<12} {format_bytes(item.size):>10} {item.name}"
        )
    if not found:
        click.echo("  no segments")


def _verify(found: list) -> None:  # type: ignore[type-arg]
    bad = 0
    frames = 0
    for item in found:
        result = read_segment(item.path)
        frames += len(result.frames)
        if not result.whole:
            bad += 1
            click.echo(f"  DAMAGED {item.name}: {result.note}", err=True)
    click.echo(f"verified {len(found)} segments, {frames} records, {bad} damaged")


def _plan(found: list, table: object, plan_size: str) -> None:  # type: ignore[type-arg]
    with reporting():
        wanted = parse_bytes(plan_size)
    plan = plan_eviction(found, table, wanted)  # type: ignore[arg-type]
    click.echo(f"to free {format_bytes(wanted)}:")
    for item in plan.victims:
        click.echo(f"  delete {item.name} ({format_bytes(item.size)})")
    if plan.sufficient:
        click.echo(f"  frees {format_bytes(plan.freed)}, enough")
    else:
        click.echo(f"  frees {format_bytes(plan.freed)}, short by {format_bytes(plan.shortfall)}")
        click.echo(f"  {format_bytes(plan.protected_bytes)} is protected and cannot go")
