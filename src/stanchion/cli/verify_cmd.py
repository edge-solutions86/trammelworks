"""``stanchion verify`` — reading a stick of batches back at the office.

This runs on the estate side, not on a forecourt. An engineer comes back from a
site whose modem was dead with a directory of batch files on a stick, and the
question before any of it is loaded is whether these are the batches that site
wrote.

The key comes from a file named on the command line rather than from the site
config, because the machine running this has no site config: it is somebody's
laptop, and the key it checks against is the one the estate issued to the site
whose stick this is.

Refusing is the default. ``--unsigned`` accepts a directory with no signatures
at all, which is what a stick from a site commissioned before signing looks
like, and it says so loudly on every line rather than passing them off as
verified.
"""

from __future__ import annotations

from pathlib import Path

import click

from stanchion.cli.main import cli, fail, reporting
from stanchion.clocks import SystemClock
from stanchion.replay import replay
from stanchion.ship.signing import read_key

EXIT_REFUSED = 3


@cli.command("verify")
@click.argument("directory", type=click.Path(exists=True, file_okay=False, path_type=Path))
@click.option(
    "--key",
    "key_path",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=None,
    help="The signing key this site was issued.",
)
@click.option("--key-id", default=None, help="The key id the signatures should carry.")
@click.option("--unsigned", is_flag=True, help="Accept batches that carry no signature.")
@click.option("--quiet", is_flag=True, help="Print the refused ones only.")
def verify_command(
    directory: Path,
    key_path: Path | None,
    key_id: str | None,
    unsigned: bool,
    quiet: bool,
) -> None:
    """Check a directory of batch files against a site's signing key."""
    if key_path is not None and key_id is None:
        fail("a key needs its key id, pass --key-id")
    if key_path is None and not unsigned:
        fail("pass --key with --key-id, or --unsigned to accept what is there")
    with reporting():
        key = None if key_path is None else read_key(key_path, key_id or "")
        report = replay(directory, key, SystemClock().now(), require_signature=not unsigned)
    if not report.verdicts:
        click.echo("no batch files in that directory")
        return
    for verdict in report.verdicts:
        if verdict.ok and quiet:
            continue
        click.echo(str(verdict))
    click.echo(str(report))
    if unsigned and key is None:
        click.echo("nothing was verified, these batches carry no signature", err=True)
    if report.refused:
        raise SystemExit(EXIT_REFUSED)
