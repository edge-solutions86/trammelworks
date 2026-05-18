"""``stanchion units`` and ``stanchion housekeep`` — commissioning and the timer.

``units`` prints the systemd units this site should run, or writes them into a
directory. It never touches ``/etc/systemd/system`` itself and it never reloads
the daemon. Generating the file and installing it are different decisions, and
an engineer who is about to change what a forecourt runs should get to read the
thing first. ``--to`` writes them somewhere for review, and copying them into
place is a deliberate second command.

``housekeep`` is what the generated timer runs: retention, then the cap, then
the vacuum. It is the same pass the collector would do on its own schedule, put
behind a command so the timer can run it on a box where the collector is busy.
"""

from __future__ import annotations

from pathlib import Path

import click

from stanchion.cli.main import Context, cli, fail, loaded, reporting
from stanchion.deploy import DEFAULT_CONFIG, Deployment, units_for, write_units
from stanchion.site import Site
from stanchion.units import format_bytes


@cli.command("units")
@click.option("--config-path", default=None, help="The config path to name inside the units.")
@click.option("--executable", default=None, help="Where stanchion is installed.")
@click.option("--user", default=None, help="The account the units run as.")
@click.option("--serve", is_flag=True, help="Include the read only query service.")
@click.option("--at", "housekeep_at", default=None, help="When housekeeping runs, HH:MM.")
@click.option(
    "--to",
    "target",
    type=click.Path(file_okay=False, path_type=Path),
    default=None,
    help="Write the units into this directory instead of printing them.",
)
@click.pass_obj
def units_command(
    obj: Context,
    config_path: str | None,
    executable: str | None,
    user: str | None,
    serve: bool,
    housekeep_at: str | None,
    target: Path | None,
) -> None:
    """Generate the systemd units for this site."""
    config = loaded(obj)
    settings: dict[str, object] = {"serve": serve}
    if config_path is not None:
        settings["config_path"] = config_path
    else:
        # The unit names the path on the box it will run on, which is where the
        # config lives after commissioning and not necessarily where it is now.
        # An engineer generating these from a working copy on a laptop gets the
        # installed path and a line saying so, rather than a refusal.
        here = str(obj.config_path)
        if here.startswith("/"):
            settings["config_path"] = here
        else:
            settings["config_path"] = DEFAULT_CONFIG
            click.echo(
                f"units will name {DEFAULT_CONFIG}, not {here}, pass --config-path to change it",
                err=True,
            )
    if executable is not None:
        settings["executable"] = executable
    if user is not None:
        settings["user"] = user
        settings["group"] = user
    if housekeep_at is not None:
        settings["housekeep_at"] = housekeep_at
    with reporting():
        deployment = Deployment(**settings)  # type: ignore[arg-type]
        built = units_for(config, deployment)
    if target is None:
        for unit in built:
            click.echo(f"# {unit.path}")
            click.echo(unit.body, nl=False)
        return
    written = write_units(built, target)
    for path in written:
        click.echo(str(path))
    click.echo(f"{len(written)} units written, none installed")


@cli.command("housekeep")
@click.option("--dry-run", is_flag=True, help="Report the footprint and change nothing.")
@click.pass_obj
def housekeep_command(obj: Context, dry_run: bool) -> None:
    """Apply retention, the size cap and the vacuum, in that order."""
    config = loaded(obj)
    if not config.archive.enabled:
        fail("this site keeps no archive, so there is nothing to housekeep")
    with reporting():
        site = Site.build(config=config)
        site.open()
    try:
        keeper = site.housekeeper()
        if keeper is None:
            fail("the archive is not open")
        if dry_run:
            found = keeper.footprint()
            click.echo(
                f"archive {format_bytes(found.total)} of {format_bytes(config.archive.max_bytes)}"
            )
            click.echo(f"{format_bytes(found.reclaimable)} would come back from a vacuum")
            return
        with reporting():
            report = keeper.run()
        click.echo(str(report))
        click.echo(
            f"{format_bytes(report.before.total)} before, {format_bytes(report.after.total)} after"
        )
    finally:
        site.close()
