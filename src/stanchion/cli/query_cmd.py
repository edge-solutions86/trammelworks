"""``stanchion query`` — asking the archive what happened.

The archive is opened read only. Not as a precaution against a bug in this
command, but because the collector is writing to the same file while somebody
runs a report against it, and a read only connection cannot take the write lock
that would stop the site collecting.

``--explain`` prints how the filter will be run before running it. On a site
with four million rows the difference between a filter SQL can index and one it
has to scan is the difference between an answer and a coffee, and being able to
see which one you typed is worth a flag.
"""

from __future__ import annotations

import click

from stanchion.archive.repository import StoredRecord
from stanchion.archive.schema import open_archive
from stanchion.cli.main import Context, cli, fail, loaded, reporting
from stanchion.clocks import SystemClock
from stanchion.query.execute import DEFAULT_SCAN_CEILING, explain, search
from stanchion.query.parser import parse_query

PREVIEW = 120


def render_record(record: StoredRecord, full: bool) -> str:
    """One record on one line, the way an engineer reads it."""
    body = record.message if full else record.message[:PREVIEW]
    body = body.replace("\n", "\\n")
    mark = "" if record.confidence in ("measured", "interpolated") else "?"
    return f"{record.best_at.isoformat()}{mark} {record.unit} {record.stream.value} {body}"


@cli.command("query")
@click.argument("filter_text", required=False, default="")
@click.option("--full/--truncate", default=False, help="Print whole messages.")
@click.option("--count", "count_only", is_flag=True, help="Print the number of matches only.")
@click.option("--explain", "show_plan", is_flag=True, help="Show how the filter will be run.")
@click.option(
    "--ceiling", default=DEFAULT_SCAN_CEILING, show_default=True, help="Most rows to scan."
)
@click.pass_obj
def query_command(
    obj: Context,
    filter_text: str,
    full: bool,
    count_only: bool,
    show_plan: bool,
    ceiling: int,
) -> None:
    """Search the local archive with a filter expression."""
    config = loaded(obj)
    if not config.archive.enabled:
        fail("this site keeps no archive, so there is nothing to query")
    with reporting():
        query = parse_query(filter_text, SystemClock().now())
    if show_plan:
        click.echo(explain(query), err=True)
    with reporting():
        connection = open_archive(config.archive.file, read_only=True)
        found = search(connection, query, ceiling=ceiling)
    if count_only:
        click.echo(str(found.count))
    else:
        for record in found.records:
            click.echo(render_record(record, full))
    if found.truncated:
        click.echo(f"stopped after scanning {found.scanned} rows, narrow the filter", err=True)
    if obj.verbose:
        click.echo(found.describe(), err=True)
