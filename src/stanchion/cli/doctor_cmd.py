"""``stanchion doctor`` — the command a commissioning sheet ends with.

The exit code is the useful part. Zero means the site works, one means it does
not, and two means it works but somebody should raise a ticket. A commissioning
script can branch on that without parsing anything, which is what makes it
usable as the last line of an install.

``--strict`` collapses warnings into failures, for the estate's own acceptance
run where a site is not signed off with a known problem on it.
"""

from __future__ import annotations

import json

import click

from stanchion.cli.main import Context, cli, reporting
from stanchion.doctor import Level, run_checks

EXIT_HEALTHY = 0
EXIT_BROKEN = 1
EXIT_WARNED = 2


@cli.command("doctor")
@click.option("--strict", is_flag=True, help="Treat a warning as a failure.")
@click.option("--json", "as_json", is_flag=True, help="Write the machine readable form.")
@click.option("--quiet", is_flag=True, help="Print only failures and warnings.")
@click.pass_obj
def doctor_command(obj: Context, strict: bool, as_json: bool, quiet: bool) -> None:
    """Check that this site will still be working next week."""
    with reporting(EXIT_BROKEN):
        config = obj.load()
    report = run_checks(config)
    if as_json:
        click.echo(
            json.dumps(
                {
                    "site": config.site,
                    "healthy": report.healthy,
                    "findings": [
                        {"level": item.level.value, "check": item.check, "message": item.message}
                        for item in report.findings
                    ],
                },
                sort_keys=True,
                indent=1,
            )
        )
    else:
        _print_text(report, quiet, config.site)
    raise SystemExit(_exit_code(report, strict))


def _print_text(report: object, quiet: bool, site: str) -> None:
    findings = report.findings  # type: ignore[attr-defined]
    click.echo(f"checking {site}")
    for item in findings:
        if quiet and item.level is Level.NOTE:
            continue
        click.echo(str(item))
    failures = report.failures  # type: ignore[attr-defined]
    warnings = report.warnings  # type: ignore[attr-defined]
    click.echo(f"{len(failures)} failures, {len(warnings)} warnings, {len(findings)} checks")


def _exit_code(report: object, strict: bool) -> int:
    if report.failures:  # type: ignore[attr-defined]
        return EXIT_BROKEN
    if report.warnings:  # type: ignore[attr-defined]
        return EXIT_BROKEN if strict else EXIT_WARNED
    return EXIT_HEALTHY
