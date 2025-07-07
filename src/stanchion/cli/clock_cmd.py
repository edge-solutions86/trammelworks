"""``stanchion clock`` — what the box believes about its own time.

This is the command that settles the argument. When a shift's records look an
hour out, the question is whether the clock was wrong or the records were, and
the answer is in the session ledger: which boots we have seen, when each was
corrected and by how much, and what the crystal's drift rate works out at.

The rate is the number worth reading. A controller that comes back at forty
parts per million every time has a dying RTC battery and wants a van visit; one
that swings between plus and minus two hundred is in a cabinet with no
ventilation, which is a different fix.
"""

from __future__ import annotations

from pathlib import Path, PurePosixPath

import click

from stanchion.cli.main import Context, cli, fail
from stanchion.clockfix.drift import DriftModel
from stanchion.clockfix.session import BootSession
from stanchion.clockfix.store import ClockStore
from stanchion.errors import StanchionError
from stanchion.timestamps import NS_PER_MS, NS_PER_SECOND

STATE_DIRNAME = "state"


def describe_rate(model: DriftModel) -> str:
    rate = model.rate_ppm()
    if rate is None:
        return "rate unknown, needs two syncs in one boot"
    return f"rate {rate:+.1f} ppm"


def describe_session(session: BootSession, index: int) -> list[str]:
    lines = [
        f"boot {index} {session.boot_id}",
        f"  up for {session.duration_ns / NS_PER_SECOND:.0f}s, {len(session.syncs)} syncs",
    ]
    for event in session.syncs:
        drift = event.offset_ns / NS_PER_MS
        flag = " LARGE" if event.large else ""
        lines.append(
            f"  at {event.monotonic_ns / NS_PER_SECOND:9.1f}s  {event.source:<10}"
            f" {drift:+11.1f} ms{flag}"
        )
    lines.append(f"  {describe_rate(DriftModel(session=session))}")
    return lines


@cli.command("clock")
@click.option("--all-boots", is_flag=True, help="Show every remembered boot, not just the last.")
@click.pass_obj
def clock_command(obj: Context, all_boots: bool) -> None:
    """Show the boot sessions and the clock corrections seen on this site."""
    try:
        config = obj.load()
    except StanchionError as exc:
        fail(f"{exc.code}: {exc}")
        return
    store = ClockStore(directory=Path(_state_dir(config.spool.directory)))
    ledger = store.load()
    if not ledger.order:
        click.echo("no boot sessions recorded")
        return
    click.echo(f"site {config.site}")
    click.echo(f"{len(ledger.order)} boots remembered, {ledger.reboots} restarts")
    if store.corrupt:
        click.echo("the stored ledger was unreadable and has been treated as empty", err=True)
    chosen = ledger.order if all_boots else ledger.order[-1:]
    for index, boot_id in enumerate(chosen):
        for line in describe_session(ledger.sessions[boot_id], index):
            click.echo(line)


def _state_dir(spool_directory: str) -> str:
    """The clock ledger lives beside the spool, not inside it.

    Inside would mean an eviction sweep could delete it, and losing the ledger
    turns every corrected record on the box back into a guess.
    """
    return str(PurePosixPath(spool_directory).parent / STATE_DIRNAME)
