"""``stanchion hold`` and ``stanchion sweep`` — the retention controls.

A hold is placed by a person, usually in a hurry, usually because a card scheme
or a lawyer has asked for records that retention is about to delete. So placing
one takes a name and a reason and nothing else is required: a hold with no window
covers everything, which is the safe direction to be wrong in.

``sweep`` is what the timer runs. It is also runnable by hand with ``--dry-run``,
because "what would this delete" is the question anyone asks before letting a
retention change loose on a site with a year of payment records on it.
"""

from __future__ import annotations

import click

from stanchion.archive.retention import Hold, format_retention
from stanchion.archive.schema import open_archive
from stanchion.archive.sweeper import HoldStore, Sweeper
from stanchion.cli.main import Context, cli, fail
from stanchion.clocks import SystemClock
from stanchion.errors import StanchionError
from stanchion.query.parser import parse_instant
from stanchion.timestamps import NanoTime


def _archive_or_fail(obj: Context, read_only: bool = False):  # type: ignore[no-untyped-def]
    config = obj.load()
    if not config.archive.enabled:
        raise StanchionError("this site keeps no archive")
    return config, open_archive(config.archive.file, read_only=read_only)


@cli.group("hold")
def hold_group() -> None:
    """Place, lift and list the legal holds on this site."""


@hold_group.command("list")
@click.pass_obj
def hold_list(obj: Context) -> None:
    """Show every hold currently in force."""
    try:
        _, connection = _archive_or_fail(obj, read_only=True)
    except StanchionError as exc:
        fail(f"{exc.code}: {exc}")
        return
    holds = HoldStore(connection=connection).all()
    if not holds:
        click.echo("no holds")
        return
    for hold in holds:
        window = "everything" if hold.open_ended else _window(hold)
        click.echo(f"{hold.name:<20} {window}  {hold.reason}")


def _window(hold: Hold) -> str:
    start = hold.covers_from.isoformat() if hold.covers_from is not None else "the beginning"
    end = hold.covers_until.isoformat() if hold.covers_until is not None else "now"
    return f"{start} .. {end}"


@hold_group.command("place")
@click.argument("name")
@click.argument("reason")
@click.option("--from", "from_text", default=None, help="Start of the window.")
@click.option("--until", "until_text", default=None, help="End of the window.")
@click.pass_obj
def hold_place(obj: Context, name: str, reason: str, from_text: str, until_text: str) -> None:
    """Stop retention deleting records, by name and reason."""
    now = SystemClock().now()
    try:
        _, connection = _archive_or_fail(obj)
        hold = Hold(
            name=name,
            reason=reason,
            placed_at=now,
            covers_from=_instant(from_text, now),
            covers_until=_instant(until_text, now),
        )
    except StanchionError as exc:
        fail(f"{exc.code}: {exc}")
        return
    HoldStore(connection=connection).place(hold)
    click.echo(f"held {name}")


def _instant(text: str | None, now: NanoTime) -> NanoTime | None:
    return None if text is None else NanoTime(parse_instant(text, now))


@hold_group.command("lift")
@click.argument("name")
@click.pass_obj
def hold_lift(obj: Context, name: str) -> None:
    """Remove a hold, letting retention have those records again."""
    try:
        _, connection = _archive_or_fail(obj)
    except StanchionError as exc:
        fail(f"{exc.code}: {exc}")
        return
    if HoldStore(connection=connection).lift(name):
        click.echo(f"lifted {name}")
        return
    fail(f"there is no hold called {name}")


@cli.command("sweep")
@click.option("--dry-run", is_flag=True, help="Say what would go without deleting it.")
@click.pass_obj
def sweep_command(obj: Context, dry_run: bool) -> None:
    """Apply the retention policy to the archive."""
    try:
        config, connection = _archive_or_fail(obj)
    except StanchionError as exc:
        fail(f"{exc.code}: {exc}")
        return
    policy = config.archive.retention
    click.echo(policy.describe())
    now = SystemClock().now()
    sweeper = Sweeper(connection=connection, policy=policy, max_deletes=config.archive.max_deletes)
    if dry_run:
        rows = sweeper.candidates(now, config.archive.max_deletes)
        click.echo(f"{len(rows)} records are old enough to be considered")
        for klass in sorted({str(row["klass"]) for row in rows}):
            window = format_retention(policy.window_for(klass))
            click.echo(f"  {klass:<12} kept for {window}")
        return
    report = sweeper.sweep(now)
    click.echo(str(report))
    for klass, count in sorted(report.by_class.items()):
        click.echo(f"  {klass:<12} {count}")
