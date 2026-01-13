"""``stanchion report`` and ``stanchion summary``.

``report`` is ``query`` with a format flag and a file to write to. They are
separate commands rather than one with more options because they are used
differently: query is typed, read and typed again, while report is what a
runbook says to run and paste into a case.

``summary`` is what the nightly timer runs. It writes the same document the
estate collects, so an engineer on site sees exactly what head office will see,
which removes an entire class of support call.
"""

from __future__ import annotations

import sys
from pathlib import Path

import click

from stanchion.archive.repository import Repository
from stanchion.archive.schema import open_archive
from stanchion.cli.main import Context, cli, fail
from stanchion.clocks import SystemClock
from stanchion.errors import StanchionError
from stanchion.query.execute import search
from stanchion.query.parser import parse_query
from stanchion.report.render import RenderOptions, parse_format, render
from stanchion.report.summary import Summary
from stanchion.spool.budget import measure
from stanchion.spool.segment import list_segments

EXIT_EMPTY = 4


@cli.command("report")
@click.argument("filter_text", required=False, default="")
@click.option("--format", "format_name", default="text", show_default=True, help="Output format.")
@click.option(
    "--out",
    "out_path",
    type=click.Path(dir_okay=False, path_type=Path),
    default=None,
    help="Write here instead of standard output.",
)
@click.option("--width", default=80, show_default=True, help="Message column width.")
@click.option("--no-header", is_flag=True, help="Leave the header row out.")
@click.option("--raw-time", is_flag=True, help="Show what the box claimed, not the correction.")
@click.pass_obj
def report_command(
    obj: Context,
    filter_text: str,
    format_name: str,
    out_path: Path | None,
    width: int,
    no_header: bool,
    raw_time: bool,
) -> None:
    """Write a report of archived records."""
    try:
        config = obj.load()
        fmt = parse_format(format_name)
        options = RenderOptions(fmt=fmt, width=width, header=not no_header, raw_time=raw_time)
        query = parse_query(filter_text, SystemClock().now())
    except StanchionError as exc:
        fail(f"{exc.code}: {exc}")
        return
    if not config.archive.enabled:
        fail("this site keeps no archive, so there is nothing to report on")
        return
    try:
        connection = open_archive(config.archive.file, read_only=True)
        found = search(connection, query)
    except StanchionError as exc:
        fail(f"{exc.code}: {exc}")
        return
    body = render(found.records, options)
    if out_path is None:
        click.echo(body)
    else:
        out_path.write_text(body + "\n", encoding="utf-8", newline="\n")
        click.echo(f"wrote {found.count} records to {out_path}", err=True)
    if found.truncated:
        click.echo("the report was truncated, narrow the filter", err=True)
    if found.count == 0:
        raise SystemExit(EXIT_EMPTY)


@cli.command("summary")
@click.option("--json", "as_json", is_flag=True, help="Write the machine readable form.")
@click.pass_obj
def summary_command(obj: Context, as_json: bool) -> None:
    """Write the nightly summary for this site."""
    try:
        config = obj.load()
    except StanchionError as exc:
        fail(f"{exc.code}: {exc}")
        return
    now = SystemClock().now()
    built = Summary(site=config.site, at=now)
    _fill_spool(built, config)
    _fill_archive(built, config)
    built.check()
    click.echo(built.render_json() if as_json else built.render_text())


def _fill_spool(built: Summary, config: object) -> None:
    settings = config.spool  # type: ignore[attr-defined]
    segments = list_segments(settings.path)
    usage = measure(segments)
    built.spool.used = usage.used
    built.spool.limit = settings.budget_bytes
    built.spool.segments = usage.segments


def _fill_archive(built: Summary, config: object) -> None:
    settings = config.archive  # type: ignore[attr-defined]
    if not settings.enabled or not settings.file.exists():
        return
    try:
        connection = open_archive(settings.file, read_only=True)
    except StanchionError as exc:
        print(f"the archive could not be read: {exc}", file=sys.stderr)
        return
    repo = Repository(connection=connection)
    built.archived = repo.count()
