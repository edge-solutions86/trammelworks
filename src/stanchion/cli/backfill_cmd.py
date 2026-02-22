"""``stanchion backfill`` — reading the files the tailer never will.

Never automatic. A day of rotated logs is a lot of metered bytes and the person
running this is deciding to spend them, usually because an incident review needs
the shift that the site was offline for.

``--dry-run`` first, always. It lists the files and what they hold without
putting a single record into the spool, and on a site whose budget is already
tight that is the difference between a backfill and an eviction.
"""

from __future__ import annotations

from pathlib import Path

import click

from stanchion.cli.main import Context, cli, fail
from stanchion.errors import StanchionError
from stanchion.ingest.backfill import DEFAULT_MAX_BYTES, Backfiller
from stanchion.ingest.discover import discover
from stanchion.paths import LogPathInfo
from stanchion.site import Site
from stanchion.units import format_bytes, parse_bytes


@cli.command("backfill")
@click.option("--unit", "units", multiple=True, help="Only these units.")
@click.option("--dry-run", is_flag=True, help="List what would be read, read nothing.")
@click.option("--max-bytes", default=None, help="Refuse a rotated file larger than this.")
@click.pass_obj
def backfill_command(
    obj: Context, units: tuple[str, ...], dry_run: bool, max_bytes: str | None
) -> None:
    """Read the rotated log files into the spool and the archive."""
    try:
        config = obj.load()
        cap = DEFAULT_MAX_BYTES if max_bytes is None else parse_bytes(max_bytes)
        found = discover(Path(config.ingest.log_root), config.ingest.units)
    except StanchionError as exc:
        fail(f"{exc.code}: {exc}")
        return
    wanted = [info for info in found.follow if not units or info.unit in units]
    if not wanted:
        click.echo("no followed files, so nothing to backfill")
        return
    if dry_run:
        _describe(wanted, cap)
        return
    site = Site.build(config=config)
    site.open()
    total = 0
    try:
        for info in wanted:
            reader = Backfiller(site=config.site, info=info, max_bytes=cap)
            report = site.absorb(list(reader.records()))
            total += report.shipped_to_spool
            click.echo(f"{info.unit:<20} {reader.report}")
    finally:
        site.close()
    click.echo(f"{total} records backfilled")


def _describe(wanted: list[LogPathInfo], cap: int) -> None:
    total = 0
    for info in wanted:
        sources = Backfiller(site="US-XX-0000", info=info, max_bytes=cap).sources()
        held = sum(_size(item.path) for item in sources)
        total += held
        click.echo(f"{info.unit:<20} {len(sources)} rotated files, {format_bytes(held)}")
    click.echo(f"{format_bytes(total)} would be read")


def _size(path: str) -> int:
    try:
        return Path(path).stat().st_size
    except OSError:
        return 0
