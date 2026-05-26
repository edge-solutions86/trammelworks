"""``stanchion count`` — the shape of what happened, not the lines.

The first question of an incident review is which unit went noisy and when, and
the answer to that is a couple of dozen numbers rather than a couple of million
lines. This is the command that produces them, so an engineer on a laptop at the
end of a VPN can find the window worth reading before pulling any of it.

``--by`` groups on a field. ``--every`` groups into time buckets instead. One or
the other, never both at once: a table that is two dimensional in a terminal is
a table nobody reads, and asking for the second dimension is a sign the filter
should have been narrowed first.

The bars are drawn from the counts and are honest about it. The widest group
fills the column and everything else is drawn relative to it, so the picture is
about proportion and the number beside it is the fact.
"""

from __future__ import annotations

import click

from stanchion.cli.main import Context, cli, fail, reading_archive, reporting
from stanchion.clocks import SystemClock
from stanchion.query.aggregate import GROUPABLE, Group, Tally, count_by, count_over_time
from stanchion.query.execute import DEFAULT_SCAN_CEILING
from stanchion.query.parser import parse_query
from stanchion.timestamps import NanoTime
from stanchion.units import parse_duration

BAR_WIDTH = 32
KEY_WIDTH = 24


def bar(count: int, largest: int, width: int = BAR_WIDTH) -> str:
    """A row of blocks in proportion to the biggest group.

    One block minimum for anything that happened at all. A group rounding to no
    bar reads as a group with no records, and the whole point of the column is
    to show that something was there.
    """
    if largest < 1 or count < 1:
        return ""
    filled = max(1, round(width * count / largest))
    return "#" * min(filled, width)


def render_group(item: Group, largest: int, as_time: bool) -> str:
    key = NanoTime(int(item.key)).isoformat() if as_time else item.key
    return f"{key:<{KEY_WIDTH}} {item.count:>8}  {bar(item.count, largest)}"


def render(tally: Tally, as_time: bool) -> list[str]:
    top = tally.largest
    largest = top.count if top is not None else 0
    ordered = sorted(tally.groups, key=lambda item: int(item.key)) if as_time else tally.groups
    return [render_group(item, largest, as_time) for item in ordered]


@cli.command("count")
@click.argument("filter_text", required=False, default="")
@click.option(
    "--by", "group", default=None, help=f"Group by one of: {', '.join(sorted(GROUPABLE))}."
)
@click.option("--every", "bucket", default=None, help="Group into time buckets of this width.")
@click.option(
    "--ceiling", default=DEFAULT_SCAN_CEILING, show_default=True, help="Most rows to scan."
)
@click.pass_obj
def count_command(
    obj: Context, filter_text: str, group: str | None, bucket: str | None, ceiling: int
) -> None:
    """Count matching records, grouped by a field or over time."""
    if group is not None and bucket is not None:
        fail("group by a field or over time, not both at once")
    with reporting():
        query = parse_query(filter_text, SystemClock().now())
        width = None if bucket is None else parse_duration(bucket)
    _, connection = reading_archive(obj, "count")
    with reporting():
        if width is not None:
            tally = count_over_time(connection, query, bucket=width, ceiling=ceiling)
        else:
            tally = count_by(connection, query, group or "unit", ceiling=ceiling)
    if not tally.groups:
        click.echo("nothing matched")
        return
    for line in render(tally, as_time=width is not None):
        click.echo(line)
    click.echo(f"{tally.total} records in {len(tally.groups)} groups")
    if tally.truncated:
        click.echo(f"stopped after scanning {tally.scanned} rows, narrow the filter", err=True)
    if obj.verbose:
        click.echo(tally.describe(), err=True)
