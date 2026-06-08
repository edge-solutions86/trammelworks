"""The self check an engineer runs before driving away.

Commissioning a forecourt takes an afternoon and the person doing it will not be
back for months. The question they need answered before they leave is not "did
the config parse" but "will this box still be collecting, redacting and shipping
next Tuesday", and those are different questions.

So every check here does the real thing rather than inspecting a setting. It
opens the directories, writes a byte and removes it, runs a card number through
the actual redaction pass, and asks the archive for a row. A check that reads a
config field and pronounces it fine is a check that passes on a box with a full
disk.

Findings are one of three kinds, and the difference is what somebody does about
it:

* **fail** — the site will not work. Fix it now, before leaving.
* **warn** — the site works and something will bite later. Raise a ticket.
* **note** — worth knowing, needs nothing.
"""

from __future__ import annotations

import os
import shutil
import tempfile
from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path

from stanchion.archive.repository import Repository
from stanchion.archive.schema import open_archive
from stanchion.config import SiteConfig
from stanchion.errors import StanchionError
from stanchion.ingest.discover import discover
from stanchion.redact.engine import Redactor
from stanchion.spool.budget import measure
from stanchion.spool.segment import list_segments
from stanchion.units import format_bytes

#: A number that is a card number, used to prove the pass actually fires.
PROBE_PAN = "4111111111111111"


class Level(StrEnum):
    """How much somebody should care."""

    FAIL = "fail"
    WARN = "warn"
    NOTE = "note"


@dataclass(frozen=True, slots=True)
class Finding:
    """One thing the check learned."""

    level: Level
    check: str
    message: str

    def __str__(self) -> str:
        return f"{self.level.value.upper():<4} {self.check:<18} {self.message}"


@dataclass(slots=True)
class Report:
    """Everything the check learned."""

    findings: list[Finding] = field(default_factory=list)

    def add(self, level: Level, check: str, message: str) -> None:
        self.findings.append(Finding(level=level, check=check, message=message))

    def fail(self, check: str, message: str) -> None:
        self.add(Level.FAIL, check, message)

    def warn(self, check: str, message: str) -> None:
        self.add(Level.WARN, check, message)

    def note(self, check: str, message: str) -> None:
        self.add(Level.NOTE, check, message)

    @property
    def failures(self) -> list[Finding]:
        return [item for item in self.findings if item.level is Level.FAIL]

    @property
    def warnings(self) -> list[Finding]:
        return [item for item in self.findings if item.level is Level.WARN]

    @property
    def healthy(self) -> bool:
        return not self.failures

    def render(self) -> str:
        lines = [str(item) for item in self.findings]
        lines.append(
            f"{len(self.failures)} failures, {len(self.warnings)} warnings, "
            f"{len(self.findings)} checks"
        )
        return "\n".join(lines)


def check_writable(path: Path) -> str | None:
    """Actually write into a directory. Returns why not, or None."""
    try:
        path.mkdir(parents=True, exist_ok=True)
        handle, name = tempfile.mkstemp(dir=path, suffix=".probe")
        os.close(handle)
        Path(name).unlink()
    except OSError as exc:
        return str(exc)
    return None


def check_log_root(config: SiteConfig, report: Report) -> None:
    root = Path(config.ingest.log_root)
    if not root.exists():
        report.fail("log root", f"{root} does not exist")
        return
    found = discover(root, config.ingest.units)
    if not found.follow:
        report.fail("log root", f"{root} holds no container log files we can read")
        return
    report.note("log root", f"following {len(found.follow)} files: {', '.join(found.units)}")
    for reason, count in sorted(found.reasons().items()):
        if reason.value == "unparseable" and count:
            report.warn("log root", f"{count} files under the root were not recognised")


def check_spool(config: SiteConfig, report: Report) -> None:
    settings = config.spool
    problem = check_writable(settings.path)
    if problem is not None:
        report.fail("spool", f"cannot write to {settings.directory}: {problem}")
        return
    usage = measure(list_segments(settings.path))
    budget = settings.budget()
    ratio = budget.fill_ratio(usage)
    report.note("spool", f"{usage.segments} segments, {100 * ratio:.1f}% of the budget")
    if ratio >= 0.8:
        report.warn("spool", "the spool is more than 80% full")


def check_redaction(config: SiteConfig, report: Report) -> None:
    if not config.redact.enabled:
        report.warn("redaction", "redaction is turned off on this site")
        return
    redactor = Redactor(
        rules=list(config.redact.rules),
        keep_first=config.redact.keep_first,
        keep_last=config.redact.keep_last,
    )
    found = redactor.redact_text(f"probe pan={PROBE_PAN}")
    if PROBE_PAN in found.text:
        report.fail("redaction", "a known test card number survived the pass")
        return
    report.note("redaction", f"card numbers are masked, {len(config.redact.rules)} site rules")


def check_archive(config: SiteConfig, report: Report) -> None:
    settings = config.archive
    if not settings.enabled:
        report.note("archive", "this site keeps no archive")
        return
    problem = check_writable(settings.file.parent)
    if problem is not None:
        report.fail("archive", f"cannot write beside {settings.path}: {problem}")
        return
    try:
        connection = open_archive(settings.file)
        count = Repository(connection=connection).count()
    except StanchionError as exc:
        report.fail("archive", f"the archive will not open: {exc}")
        return
    report.note("archive", f"{count} records, {settings.retention.describe()}")


def check_ship(config: SiteConfig, report: Report) -> None:
    settings = config.ship
    if settings.to_file:
        problem = check_writable(settings.file_directory)
        if problem is not None:
            report.fail("ship", f"cannot write to the outbox: {problem}")
            return
        report.note("ship", f"writing batches to {settings.file_directory}")
        return
    if settings.endpoint.startswith("http://"):
        report.warn("ship", "the collector endpoint is plain http")
    if "invalid" in settings.endpoint:
        report.fail("ship", f"the endpoint is still the placeholder: {settings.endpoint}")
        return
    report.note("ship", f"shipping to {settings.endpoint}")


def check_state(config: SiteConfig, report: Report) -> None:
    from stanchion.site import state_directory

    state = state_directory(config.spool.directory)
    problem = check_writable(state)
    if problem is not None:
        report.fail("state", f"cannot write to {state}: {problem}")
        return
    report.note("state", f"state directory is {state}")


def check_signing(config: SiteConfig, report: Report) -> None:
    """A site that says it signs has to be able to."""
    settings = config.ship
    if not settings.signs:
        report.note("signing", "batches are not signed by this site")
        return
    try:
        key = settings.signing_key()
    except StanchionError as exc:
        report.fail("signing", f"the signing key will not load: {exc}")
        return
    if key is None:
        report.fail("signing", "signing is configured but no key was produced")
        return
    report.note("signing", f"signing as {key.key_id}")


def check_space(config: SiteConfig, report: Report) -> None:
    """Whether the two disk budgets can both be honoured on this card.

    The spool budget and the archive cap are set separately and nothing has
    checked them against each other before now. Together they are what this
    site will occupy when both are full, and a commissioning sheet that adds
    up to more than the card holds is the kind of error that only shows up
    months later on the night both fill.
    """
    spool = config.spool.budget_bytes
    archive = config.archive.max_bytes if config.archive.enabled else 0
    total = spool + archive
    free = free_space(config.spool.path)
    report.note(
        "space",
        f"spool {format_bytes(spool)} plus archive {format_bytes(archive)} "
        f"is {format_bytes(total)}",
    )
    if free is None:
        return
    if total > free:
        report.fail(
            "space",
            f"the budgets need {format_bytes(total)} and the disk has {format_bytes(free)}",
        )
    elif total > free * CROWDED:
        report.warn(
            "space",
            f"the budgets take most of the disk, {format_bytes(free)} free",
        )


def check_compaction(config: SiteConfig, report: Report) -> None:
    """Whether anything will ever give the disk back."""
    if not config.ship.compact:
        report.warn("compaction", "part shipped segments are never compacted")
        return
    if config.archive.enabled and not config.archive.vacuum:
        report.warn("compaction", "the archive is never vacuumed, so deletions free nothing")
        return
    report.note("compaction", "the spool compacts and the archive vacuums")


def free_space(path: Path) -> int | None:
    """What the filesystem says is left, or None when it will not say."""
    target = path
    while not target.exists() and target != target.parent:
        target = target.parent
    try:
        return shutil.disk_usage(target).free
    except OSError:
        return None


#: Above this fraction of the free disk, the budgets are worth a warning.
CROWDED = 0.8


CHECKS = (
    check_log_root,
    check_spool,
    check_redaction,
    check_archive,
    check_ship,
    check_signing,
    check_space,
    check_compaction,
    check_state,
)


def run_checks(config: SiteConfig) -> Report:
    """Run every check against a real site."""
    report = Report()
    for check in CHECKS:
        check(config, report)
    return report
