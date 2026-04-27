"""The routes, as a pure function of a request.

No sockets in this module and that is the point. A handler that reaches for the
network cannot be tested without one, and the interesting behaviour here is all
in the deciding: what a filter is allowed to ask for, what a missing token gets
back, what happens when the archive is not there.

The service is **read only and it is not a general API**. It exists for two
callers: the touchscreen in the kiosk behind the counter, which shows the site's
own status, and the estate poller, which collects the nightly summary from sites
whose uplink cannot push. Neither needs to write, so nothing here can.

Every response is JSON, including the errors. A caller parsing a mixture of JSON
and HTML error pages ends up guessing, and the one time it guesses wrong is the
outage.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import parse_qs, urlsplit

from stanchion.archive.repository import Repository
from stanchion.archive.schema import open_archive
from stanchion.config import SiteConfig
from stanchion.errors import StanchionError
from stanchion.query.cursor import parse_cursor
from stanchion.query.execute import search
from stanchion.query.parser import parse_query
from stanchion.report.summary import Summary
from stanchion.spool.budget import measure
from stanchion.spool.segment import list_segments
from stanchion.timestamps import NanoTime

MAX_LIMIT = 500
DEFAULT_LIMIT = 100


@dataclass(frozen=True, slots=True)
class Request:
    """What arrived."""

    path: str
    query: dict[str, list[str]] = field(default_factory=dict)
    headers: dict[str, str] = field(default_factory=dict)
    method: str = "GET"

    @classmethod
    def of(cls, target: str, headers: dict[str, str] | None = None, method: str = "GET") -> Request:
        parts = urlsplit(target)
        return cls(
            path=parts.path.rstrip("/") or "/",
            query=parse_qs(parts.query),
            headers={key.lower(): value for key, value in (headers or {}).items()},
            method=method.upper(),
        )

    def one(self, name: str, fallback: str = "") -> str:
        values = self.query.get(name)
        return values[0] if values else fallback

    @property
    def token(self) -> str:
        raw = self.headers.get("authorization", "")
        return raw[len("Bearer ") :] if raw.startswith("Bearer ") else ""


@dataclass(frozen=True, slots=True)
class Response:
    """What goes back."""

    status: int
    payload: dict[str, Any]

    def body(self) -> bytes:
        return json.dumps(self.payload, sort_keys=True, indent=1).encode("utf-8")

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300


def problem(status: int, message: str, **extra: Any) -> Response:
    payload: dict[str, Any] = {"error": message}
    payload.update(extra)
    return Response(status=status, payload=payload)


@dataclass(slots=True)
class Service:
    """The routes for one site."""

    config: SiteConfig
    now: NanoTime
    token: str = ""

    def handle(self, request: Request) -> Response:
        """Route one request, refusing anything that is not a read."""
        if request.method not in ("GET", "HEAD"):
            return problem(405, "this service only reads")
        if self.token and request.token != self.token:
            return problem(401, "a bearer token is required")
        route = ROUTES.get(request.path)
        if route is None:
            return problem(404, "no such route", routes=sorted(ROUTES))
        try:
            return route(self, request)
        except StanchionError as exc:
            return problem(400, exc.message, code=exc.code, detail=exc.detail or "")

    def health(self, request: Request) -> Response:
        return Response(
            status=200,
            payload={"site": self.config.site, "status": "ok", "at": self.now.isoformat()},
        )

    def summary(self, request: Request) -> Response:
        built = Summary(site=self.config.site, at=self.now)
        usage = measure(list_segments(self.config.spool.path))
        built.spool.used = usage.used
        built.spool.limit = self.config.spool.budget_bytes
        built.spool.segments = usage.segments
        if self.config.archive.enabled and self.config.archive.file.exists():
            connection = open_archive(self.config.archive.file, read_only=True)
            built.archived = Repository(connection=connection).count()
        built.check()
        return Response(status=200, payload=built.as_dict())

    def records(self, request: Request) -> Response:
        if not self.config.archive.enabled:
            return problem(409, "this site keeps no archive")
        if not self.config.archive.file.exists():
            return problem(404, "the archive has not been created yet")
        limit = _limit(request)
        query = parse_query(request.one("filter"), self.now)
        if query.limit is None or query.limit > limit:
            query = type(query)(where=query.where, limit=limit)
        after = request.one("after")
        connection = open_archive(self.config.archive.file, read_only=True)
        found = search(connection, query, after=parse_cursor(after) if after else None)
        payload: dict[str, Any] = {
            "site": self.config.site,
            "count": found.count,
            "truncated": found.truncated,
            "records": [_record(item) for item in found.records],
        }
        if found.next_cursor is not None:
            # Only when there might be more. A caller that follows the cursor
            # while one is offered and stops when it is not needs no count
            # arithmetic to know when it has the lot.
            payload["next"] = found.next_cursor.encode()
        return Response(status=200, payload=payload)

    def units(self, request: Request) -> Response:
        if not self.config.archive.enabled or not self.config.archive.file.exists():
            return Response(status=200, payload={"units": []})
        connection = open_archive(self.config.archive.file, read_only=True)
        repo = Repository(connection=connection)
        return Response(
            status=200,
            payload={"units": list(repo.units()), "counts": repo.counts_by_class()},
        )


def _limit(request: Request) -> int:
    raw = request.one("limit", str(DEFAULT_LIMIT))
    try:
        wanted = int(raw)
    except ValueError as exc:
        raise StanchionError("limit is not a number", detail=raw) from exc
    if wanted < 1:
        raise StanchionError("limit must be at least one", detail=raw)
    return min(wanted, MAX_LIMIT)


def _record(item: Any) -> dict[str, Any]:
    payload = {
        "observed_at": item.observed_at.isoformat(),
        "unit": item.unit,
        "class": item.klass,
        "stream": item.stream.value,
        "confidence": item.confidence,
        "message": item.message,
        "labels": dict(item.labels),
    }
    if item.corrected_at is not None:
        payload["corrected_at"] = item.corrected_at.isoformat()
    return payload


ROUTES = {
    "/": Service.health,
    "/health": Service.health,
    "/summary": Service.summary,
    "/records": Service.records,
    "/units": Service.units,
}
