"""``stanchion serve`` — the read only face, started by hand or by a unit file.

The flags mirror the safety rules in the server rather than papering over them.
``--host`` off loopback is refused unless ``--any-interface`` is also given and
a token is set, and the command says which of the two is missing instead of
failing with a bind error that means nothing to whoever typed it.

``--once`` serves a single request and stops. That is how the install check
works: start it, ask it for ``/health``, and know the service can actually run
on this box before the unit file tries it at three in the morning.
"""

from __future__ import annotations

import click

from stanchion.cli.main import Context, cli, fail
from stanchion.clocks import SystemClock
from stanchion.errors import StanchionError
from stanchion.eventlog import EventLog, Level
from stanchion.service.server import DEFAULT_HOST, DEFAULT_PORT, ServerSettings, ServiceHost


@cli.command("serve")
@click.option("--host", default=DEFAULT_HOST, show_default=True, help="Address to bind.")
@click.option("--port", default=DEFAULT_PORT, show_default=True, help="Port to bind.")
@click.option("--token", default="", help="Require this bearer token.")
@click.option(
    "--any-interface",
    is_flag=True,
    help="Allow binding somewhere the rest of the shop can reach.",
)
@click.option("--once", is_flag=True, help="Serve one request and stop.")
@click.pass_obj
def serve_command(
    obj: Context, host: str, port: int, token: str, any_interface: bool, once: bool
) -> None:
    """Serve the site's status and archive over HTTP, read only."""
    try:
        config = obj.load()
        settings = ServerSettings(
            host=host, port=port, token=token, allow_any_interface=any_interface
        )
    except StanchionError as exc:
        fail(f"{exc.code}: {exc}")
        return
    events = EventLog(minimum=Level.DEBUG if obj.verbose else Level.INFO, clock=SystemClock())
    service = ServiceHost(config=config, settings=settings, events=events)
    server = service.build()
    address = service.address
    click.echo(f"serving {config.site} on http://{address[0]}:{address[1]}", err=True)
    if settings.loopback_only:
        click.echo("bound to loopback, nothing off this box can reach it", err=True)
    else:
        click.echo("bound off loopback, a token is required for every request", err=True)
    try:
        if once:
            server.handle_request()
        else:
            server.serve_forever()
    except KeyboardInterrupt:
        click.echo("stopping", err=True)
    finally:
        service.close()
    if obj.verbose:
        for name, value in sorted(service.metrics.snapshot().items()):
            click.echo(f"{name} {value}", err=True)
