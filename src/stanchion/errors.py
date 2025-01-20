"""Exception hierarchy for stanchion.

Every failure a site controller can hit is one of these. The base carries a
short stable ``code`` because the only channel back from an unattended forecourt
is a status line in a nightly summary, and a free text message is useless for
grouping ten thousand of them.
"""

from __future__ import annotations


class StanchionError(Exception):
    """Base of every error raised by this package."""

    code = "stanchion.error"

    def __init__(self, message: str, *, detail: str | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.detail = detail

    def __str__(self) -> str:
        if self.detail:
            return f"{self.message} ({self.detail})"
        return self.message

    def as_dict(self) -> dict[str, str]:
        """Flatten to the shape the nightly summary uploads."""
        out = {"code": self.code, "message": self.message}
        if self.detail is not None:
            out["detail"] = self.detail
        return out


class ConfigError(StanchionError):
    """The site configuration is missing, malformed or self contradictory."""

    code = "stanchion.config"


class ParseError(StanchionError):
    """A log line did not match the format its reader expected."""

    code = "stanchion.parse"

    def __init__(
        self,
        message: str,
        *,
        detail: str | None = None,
        source: str | None = None,
        offset: int | None = None,
    ) -> None:
        super().__init__(message, detail=detail)
        self.source = source
        self.offset = offset

    def as_dict(self) -> dict[str, str]:
        out = super().as_dict()
        if self.source is not None:
            out["source"] = self.source
        if self.offset is not None:
            out["offset"] = str(self.offset)
        return out


class IngestError(StanchionError):
    """A reader could not make progress against a container log file."""

    code = "stanchion.ingest"


class RedactionError(StanchionError):
    """A redaction rule could not be applied, so the record must not ship."""

    code = "stanchion.redaction"


class SpoolError(StanchionError):
    """The on disk spool is unusable or out of budget."""

    code = "stanchion.spool"


class ShipError(StanchionError):
    """An upload attempt failed in a way the caller has to decide about."""

    code = "stanchion.ship"


class RetentionError(StanchionError):
    """A retention decision conflicts with a hold or a scheme floor."""

    code = "stanchion.retention"


class QueryError(StanchionError):
    """A filter expression is not valid."""

    code = "stanchion.query"
