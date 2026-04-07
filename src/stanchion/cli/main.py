"""The command line, which is how a field engineer touches a site.

Every command takes the same two things: where the config is and how loud to be.
They are on the group rather than on each command because a support call goes
"run stanchion with the config you were given and paste me what it says", and
that instruction has to work for whichever subcommand comes next.

Nothing here prints a bare traceback. A traceback on a laptop in a forecourt car
park tells the engineer nothing they can act on, so every expected failure is
caught and rendered as one line with the stable error code in it.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

import click

from stanchion.config import SiteConfig
from stanchion.configfile import dump_site_config, load_site_config
from stanchion.errors import StanchionError
from stanchion.version import VERSION

DEFAULT_CONFIG = "/etc/stanchion/site.yaml"

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_CONFIG = 2


@dataclass(slots=True)
class Context:
    """What every command needs and nothing more."""

    config_path: Path
    verbose: bool

    def load(self) -> SiteConfig:
        return load_site_config(self.config_path)


def fail(message: str, code: int = EXIT_FAILED) -> None:
    """Print one line to stderr and stop."""
    click.echo(message, err=True)
    raise SystemExit(code)


@click.group(context_settings={"help_option_names": ["-h", "--help"]})
@click.option(
    "--config",
    "config_path",
    type=click.Path(dir_okay=False, path_type=Path),
    default=DEFAULT_CONFIG,
    show_default=True,
    help="Site configuration file.",
)
@click.option("-v", "--verbose", is_flag=True, help="Show more about each step.")
@click.version_option(VERSION, prog_name="stanchion")
@click.pass_context
def cli(ctx: click.Context, config_path: Path, verbose: bool) -> None:
    """Container log aggregation for a forecourt site controller."""
    ctx.obj = Context(config_path=config_path, verbose=verbose)


@cli.command("config")
@click.option("--check", is_flag=True, help="Validate only, print nothing.")
@click.pass_obj
def config_command(obj: Context, check: bool) -> None:
    """Load the site config and print it back as it was understood."""
    try:
        loaded = obj.load()
    except StanchionError as exc:
        fail(f"{exc.code}: {exc}", EXIT_CONFIG)
        return
    if check:
        click.echo(f"ok {loaded.site}")
        return
    click.echo(dump_site_config(loaded), nl=False)


@cli.command("version")
def version_command() -> None:
    """Print the version and nothing else."""
    click.echo(VERSION)


def load_commands() -> None:
    """Attach the subcommands that live in their own modules.

    Imported here rather than at the top so that the group exists before a
    command module tries to decorate it. Circular by construction, deliberate,
    and the alternative is a registry nobody remembers to add to.
    """
    from stanchion.cli import (  # noqa: F401
        backfill_cmd,
        clock_cmd,
        count_cmd,
        doctor_cmd,
        hold_cmd,
        ingest_cmd,
        query_cmd,
        redact_cmd,
        report_cmd,
        run_cmd,
        serve_cmd,
        spool_cmd,
    )


def run(argv: list[str] | None = None) -> int:
    """Entry point that turns an expected failure into an exit code."""
    load_commands()
    try:
        cli.main(args=argv if argv is not None else sys.argv[1:], standalone_mode=False)
    except SystemExit as exc:
        return int(exc.code or EXIT_OK)
    except click.ClickException as exc:
        click.echo(exc.format_message(), err=True)
        return EXIT_CONFIG
    except click.Abort:
        click.echo("aborted", err=True)
        return EXIT_FAILED
    except StanchionError as exc:
        click.echo(f"{exc.code}: {exc}", err=True)
        return EXIT_FAILED
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(run())
