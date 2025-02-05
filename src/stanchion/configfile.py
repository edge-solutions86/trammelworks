"""Reading the site config off disk, and letting the estate override it.

Commissioning writes ``/etc/stanchion/site.yaml`` once. After that the only way
anyone changes anything on a live forecourt is an environment variable pushed by
the fleet agent, because editing YAML over a 2G fallback link on a box with no
screen is how sites end up with a config that does not parse.

So the overlay order is file, then environment, and the environment can only set
scalars that already exist. A typo in an override is an error rather than a new
setting nobody reads.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import yaml

from stanchion.config import SiteConfig, site_from_mapping
from stanchion.errors import ConfigError

ENV_PREFIX = "STANCHION_"

#: Environment name to (section, key). Only these can be overridden, and the
#: list is deliberately short: everything on it is something a support engineer
#: has genuinely needed to change on a live site.
ENV_OVERRIDES: dict[str, tuple[str, str]] = {
    "STANCHION_SITE": ("", "site"),
    "STANCHION_LOG_ROOT": ("ingest", "log_root"),
    "STANCHION_POLL_INTERVAL": ("ingest", "poll_interval"),
    "STANCHION_SPOOL_DIR": ("spool", "directory"),
    "STANCHION_SPOOL_BUDGET": ("spool", "budget"),
    "STANCHION_ENDPOINT": ("ship", "endpoint"),
    "STANCHION_MONTHLY_ALLOWANCE": ("ship", "monthly_allowance"),
    "STANCHION_BATCH": ("ship", "batch"),
}


def parse_yaml(text: str) -> dict[str, Any]:
    """Parse a config document, refusing anything that is not a mapping."""
    try:
        loaded = yaml.safe_load(text)
    except yaml.YAMLError as exc:
        raise ConfigError("config is not valid yaml", detail=str(exc).replace("\n", " ")) from exc
    if loaded is None:
        raise ConfigError("config file is empty")
    if not isinstance(loaded, Mapping):
        raise ConfigError("config must be a mapping", detail=type(loaded).__name__)
    return dict(loaded)


def apply_env(raw: dict[str, Any], environ: Mapping[str, str] | None = None) -> dict[str, Any]:
    """Overlay the permitted environment variables onto a parsed mapping."""
    env = os.environ if environ is None else environ
    merged: dict[str, Any] = {
        key: dict(value) if isinstance(value, dict) else value for key, value in raw.items()
    }
    for name, value in sorted(env.items()):
        if not name.startswith(ENV_PREFIX):
            continue
        target = ENV_OVERRIDES.get(name)
        if target is None:
            raise ConfigError("unknown environment override", detail=name)
        section, key = target
        if not section:
            merged[key] = value
            continue
        current = merged.get(section)
        block = dict(current) if isinstance(current, Mapping) else {}
        block[key] = value
        merged[section] = block
    return merged


def load_site_config(path: str | Path, environ: Mapping[str, str] | None = None) -> SiteConfig:
    """Load, overlay and validate the config for this site."""
    target = Path(path)
    try:
        text = target.read_text(encoding="utf-8")
    except FileNotFoundError as exc:
        raise ConfigError("config file not found", detail=str(target)) from exc
    except OSError as exc:
        raise ConfigError("config file unreadable", detail=str(exc)) from exc
    return site_from_mapping(apply_env(parse_yaml(text), environ))


def dump_site_config(config: SiteConfig) -> str:
    """Render a config back to YAML, for the doctor command and for support."""
    return yaml.safe_dump(config.as_dict(), sort_keys=True, default_flow_style=False)
