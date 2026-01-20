"""Putting the routes behind a socket.

Thin on purpose. Everything that decides anything lives in ``app``; this turns
bytes into a ``Request`` and a ``Response`` back into bytes, and that is all it
is allowed to do.

It binds to the loopback address unless told otherwise, and telling it otherwise
requires saying so. A forecourt controller sits on a flat network with the
dispenser controllers and the till, and a service that bound to every interface
by default would be reachable from the whole shop the first time somebody ran it
to look at a log.

The handler logs through our own event log rather than to stderr. The default
``BaseHTTPRequestHandler`` writes a line per request to stderr, which on a box
whose stderr goes to the container runtime means the service's own chatter comes
straight back round as input.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from stanchion.clocks import Clock, SystemClock
from stanchion.config import SiteConfig
from stanchion.errors import StanchionError
from stanchion.eventlog import EventLog, null_log
from stanchion.service.app import Request, Response, Service
from stanchion.telemetry import Registry

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 9787
LOOPBACK = frozenset({"127.0.0.1", "::1", "localhost"})


@dataclass(slots=True)
class ServerSettings:
    """Where the service listens and who may talk to it."""

    host: str = DEFAULT_HOST
    port: int = DEFAULT_PORT
    token: str = ""
    allow_any_interface: bool = False

    def __post_init__(self) -> None:
        # Port zero is legal and means "ask the kernel for a free one". The
        # tests use it, and so does an engineer starting a throwaway instance
        # beside the one the unit file already has on the usual port.
        if not 0 <= self.port <= 65535:
            raise StanchionError("port is out of range", detail=str(self.port))
        if self.host not in LOOPBACK and not self.allow_any_interface:
            raise StanchionError("binding off loopback needs allow_any_interface", detail=self.host)
        if self.host not in LOOPBACK and not self.token:
            raise StanchionError("a service reachable off the box needs a token")

    @property
    def loopback_only(self) -> bool:
        return self.host in LOOPBACK


@dataclass(slots=True)
class ServiceHost:
    """A running service, or one about to be."""

    config: SiteConfig
    settings: ServerSettings = field(default_factory=ServerSettings)
    clock: Clock = field(default_factory=SystemClock)
    events: EventLog = field(default_factory=null_log)
    metrics: Registry = field(default_factory=Registry)
    server: ThreadingHTTPServer | None = field(default=None, init=False)

    def service(self) -> Service:
        return Service(config=self.config, now=self.clock.now(), token=self.settings.token)

    def build(self) -> ThreadingHTTPServer:
        """Create the server without serving, so a test can drive it."""
        host = self

        class Handler(BaseHTTPRequestHandler):
            server_version = "stanchion"
            protocol_version = "HTTP/1.1"

            def do_GET(self) -> None:  # noqa: N802 - the name the base class wants
                host.respond(self, "GET")

            def do_HEAD(self) -> None:  # noqa: N802
                host.respond(self, "HEAD")

            def do_POST(self) -> None:  # noqa: N802
                host.respond(self, "POST")

            def log_message(self, fmt: str, *args: Any) -> None:
                host.events.debug("http", line=fmt % args)

        self.server = ThreadingHTTPServer((self.settings.host, self.settings.port), Handler)
        return self.server

    def respond(self, handler: BaseHTTPRequestHandler, method: str) -> None:
        """Turn one handler callback into a request and a response."""
        request = Request.of(handler.path, headers=dict(handler.headers.items()), method=method)
        answer = self.dispatch(request)
        body = answer.body()
        handler.send_response(answer.status)
        handler.send_header("Content-Type", "application/json")
        handler.send_header("Content-Length", str(len(body)))
        handler.end_headers()
        if method != "HEAD":
            handler.wfile.write(body)

    def dispatch(self, request: Request) -> Response:
        """One request, counted and logged."""
        answer = self.service().handle(request)
        self.metrics.incr("service_requests")
        if not answer.ok:
            self.metrics.incr("service_refusals")
        self.events.info(
            "service_request", path=request.path, method=request.method, status=answer.status
        )
        return answer

    @property
    def address(self) -> tuple[str, int]:
        if self.server is None:
            return (self.settings.host, self.settings.port)
        found = self.server.server_address
        return (str(found[0]), int(found[1]))

    def serve_forever(self) -> None:
        server = self.server if self.server is not None else self.build()
        self.events.info("service_started", host=self.address[0], port=self.address[1])
        try:
            server.serve_forever()
        finally:
            self.close()

    def close(self) -> None:
        if self.server is not None:
            self.server.server_close()
            self.server = None
            self.events.info("service_stopped")
