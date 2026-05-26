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
from stanchion.cli.main import Context, cli, reading_archive, reporting
from stanchion.clocks import SystemClock
from stanchion.query.cursor import parse_cursor
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
@click.option("--after", default="", help="Resume from the cursor a previous page printed.")
@click.pass_obj
def query_command(
    obj: Context,
    filter_text: str,
    full: bool,
    count_only: bool,
    show_plan: bool,
    ceiling: int,
    after: str,
) -> None:
    """Search the local archive with a filter expression."""
    with reporting():
        query = parse_query(filter_text, SystemClock().now())
    if show_plan:
        click.echo(explain(query), err=True)
    _, connection = reading_archive(obj, "query")
    with reporting():
        found = search(
            connection, query, ceiling=ceiling, after=parse_cursor(after) if after else None
        )
    if count_only:
        click.echo(str(found.count))
    else:
        for record in found.records:
            click.echo(render_record(record, full))
    if found.more and found.next_cursor is not None:
        click.echo(f"next page: --after {found.next_cursor.encode()}", err=True)
    if found.truncated:
        click.echo(f"stopped after scanning {found.scanned} rows, narrow the filter", err=True)
    if obj.verbose:
        click.echo(found.describe(), err=True)
