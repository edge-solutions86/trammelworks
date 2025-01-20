"""stanchion: container log aggregation for unattended forecourt controllers.

A forecourt site controller runs a handful of containers that between them move
fuel and take money. This package collects what those containers write, puts the
fragments back together, strips card data, holds the result inside a disk budget
and ships it over a metered link.
"""

from __future__ import annotations

from stanchion.errors import (
    ConfigError,
    IngestError,
    ParseError,
    QueryError,
    RedactionError,
    RetentionError,
    ShipError,
    SpoolError,
    StanchionError,
)
from stanchion.version import SPOOL_FORMAT, VERSION, version_string

__all__ = [
    "SPOOL_FORMAT",
    "VERSION",
    "ConfigError",
    "IngestError",
    "ParseError",
    "QueryError",
    "RedactionError",
    "RetentionError",
    "ShipError",
    "SpoolError",
    "StanchionError",
    "version_string",
]
