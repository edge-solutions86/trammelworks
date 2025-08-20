"""``stanchion redact`` — prove to somebody that a line is safe.

Two audiences and they want opposite things. An assessor wants to see a sample
of real traffic go in and come out with nothing recognisable in it. An engineer
adding a site rule wants to know why their pattern did not fire.

So the command reads lines from a file or from standard input, runs the real
pass, and prints the result with a per rule tally at the end. ``--explain``
prints which rule caught what on each line, but never what was caught, because
this command's whole job is to not be the place cardholder data ends up.

The exit code is the useful part in a script: nonzero when anything was removed.
Piping a shift of traffic through it and checking the status is a one line answer
to "does this unit leak", and it is what the nightly bench check runs.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass

import click

from stanchion.cli.main import Context, cli, fail
from stanchion.errors import StanchionError
from stanchion.redact.engine import Redactor

EXIT_CLEAN = 0
EXIT_FOUND = 3


@dataclass(slots=True)
class LineReport:
    """What one line cost."""

    number: int
    text: str
    rules: tuple[str, ...]

    @property
    def changed(self) -> bool:
        return bool(self.rules)


def read_lines(source: str | None) -> list[str]:
    """Lines from a file, or from standard input when there is no file."""
    if source is None:
        return sys.stdin.read().splitlines()
    from pathlib import Path

    try:
        return Path(source).read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as exc:
        raise StanchionError("cannot read the input", detail=str(exc)) from exc


def run_pass(redactor: Redactor, lines: list[str]) -> list[LineReport]:
    out: list[LineReport] = []
    for number, line in enumerate(lines, start=1):
        found = redactor.redact_text(line)
        names: list[str] = []
        if found.stripes:
            names.extend(["card_stripe"] * found.stripes)
        if found.pans:
            names.extend(["card_pan"] * found.pans)
        names.extend(hit.rule for hit in found.hits)
        out.append(LineReport(number=number, text=found.text, rules=tuple(names)))
    return out


@cli.command("redact")
@click.argument("source", required=False, type=click.Path(dir_okay=False))
@click.option("--explain", is_flag=True, help="Name the rules that fired on each line.")
@click.option("--quiet", is_flag=True, help="Print the tally only.")
@click.pass_obj
def redact_command(obj: Context, source: str | None, explain: bool, quiet: bool) -> None:
    """Run the site's redaction pass over some lines and report what went."""
    try:
        config = obj.load()
        lines = read_lines(source)
    except StanchionError as exc:
        fail(f"{exc.code}: {exc}")
        return
    if not config.redact.enabled:
        click.echo("redaction is disabled on this site, nothing was checked", err=True)
    redactor = Redactor(
        rules=list(config.redact.rules),
        keep_first=config.redact.keep_first,
        keep_last=config.redact.keep_last,
    )
    try:
        reports = run_pass(redactor, lines)
    except StanchionError as exc:
        fail(f"{exc.code}: {exc}")
        return
    for report in reports:
        if not quiet:
            click.echo(report.text)
        if explain and report.changed:
            click.echo(f"  line {report.number}: {', '.join(sorted(set(report.rules)))}", err=True)
    _print_tally(redactor, len(lines))
    raise SystemExit(EXIT_FOUND if redactor.audit.total_hits else EXIT_CLEAN)


def _print_tally(redactor: Redactor, lines: int) -> None:
    audit = redactor.audit
    click.echo(f"{lines} lines, {audit.total_hits} removed", err=True)
    for name, count in sorted(audit.hits.items()):
        click.echo(f"  {name:<20} {count}", err=True)
