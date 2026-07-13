# stanchion

Container log aggregation for unattended fuel forecourt site controllers.

A forecourt site controller is a small industrial PC under the shop counter. It
runs a handful of containers that between them move fuel and take money: a pump
controller bridge, an EMV payment kernel, a tank gauge poller, a price sign
driver, a loyalty sync job. Nobody logs into it. When something goes wrong the
only evidence is what those containers wrote, and by the time anyone looks the
container is gone.

`stanchion` collects that evidence. It reads the three container runtime log
formats a site can present, puts split lines back together, corrects timestamps
written by a drifting RTC, strips card data before anything leaves the site,
holds the result in a bounded on disk spool and ships it over a metered LTE
link inside a monthly byte allowance.

## What makes this different from a log shipper

Five constraints shape every decision in here, and none of them is negotiable.

**The link is metered.** Backhaul is an LTE modem on a plan sold in decimal
gigabytes. Shipping is a budgeted decision per priority class, not a best effort
loop, and a month's allowance is tracked across reboots because a site that lost
power twice a week would otherwise start fresh several times over and the estate
would find out from the bill.

**The disk is small and it is an SD card.** The spool has a hard budget and a
reserve. When it fills, records are evicted by priority class, lowest first, and
payment records are never evicted at all. Writes are batched because a commit
per record on that hardware is the difference between a pass that finishes and
one an operator kills.

**Power goes away without warning.** A half written record at the end of a
segment is the normal state of a file at boot, not an exceptional one. Records
are framed with a checksum so the good ones before the tear survive, and
recovery truncates to the last whole frame rather than discarding the file.

**The clock is wrong.** These boxes have a cheap RTC and often no NTP. Drift is
modelled as a sawtooth, corrections are recorded per boot session, and every
record carries both what the box claimed and what the correction makes of it,
with a confidence saying which.

**Card data must not leave the site.** Redaction runs before anything touches
disk and it fails closed: if a rule cannot be applied the record is refused
rather than shipped half cleaned. Track data goes first, then PANs, then the
site's own rules, and that order is load bearing.

## Getting a site running

```console
$ stanchion --config /etc/stanchion/site.yaml config --check
ok US-TX-0417

$ stanchion --config /etc/stanchion/site.yaml doctor
NOTE log_root           4 files under /var/log/containers
NOTE spool              512 MiB budget, 8 MiB segments
NOTE redaction          card numbers are masked, 2 site rules
NOTE signing            signing as tx-0417-2025
NOTE space              spool 512 MiB plus archive 2 GiB is 2.5 GiB

$ stanchion --config /etc/stanchion/site.yaml units --serve --to /tmp/units
```

`examples/site.yaml` is a commissioned site written out in full. It is loaded
and checked by the test suite, so it cannot drift away from what this build
accepts.

## The commands

| command | what it is for |
| --- | --- |
| `run` | collect and ship on a timer. What the unit file starts |
| `ship` | the uplink half on its own, for watching a backlog drain |
| `ingest` | list the container log files this site would follow |
| `backfill` | read the rotated files the tailer never saw. Never automatic |
| `query` | search the local archive with a filter expression |
| `count` | the shape of what happened, grouped or over time |
| `report` | write a report, or the nightly summary |
| `hold` / `sweep` | place a legal hold, apply retention |
| `housekeep` | retention, then the size cap, then the vacuum |
| `spool` | what is waiting and which class it is in |
| `clock` | boot sessions, corrections and the crystal's drift rate |
| `redact` | run the redaction pass over some lines and say what went |
| `serve` | the read only query service, loopback by default |
| `verify` | check a stick of batches against a site's signing key |
| `units` | generate the systemd units this site should run |
| `doctor` | check that this site will still be working next week |

## Layout

```
src/stanchion/
  ingest/    reading container logs: CRI, docker json-file, journald export
  clockfix/  boot sessions, drift estimation, reconciliation
  redact/    the fail closed redaction pass
  spool/     framing, segments, the disk budget, priority eviction
  ship/      batching, the meter, backoff, watermarks, signing, compaction
  archive/   the local SQLite store, retention, holds, space
  query/     the filter language: lexer, parser, planner, evaluator
  report/    the nightly summary and the render formats
  service/   the read only HTTP routes and the socket that serves them
  cli/       one module per command
```

## Development

```console
$ python -m pip install -r requirements-dev.txt
$ python -m pip install -e .
$ python -m pytest
$ python -m ruff check . && python -m mypy
```

The suite is the specification. It runs on 3.12 and 3.13, and CI runs the
same three checks this repository is developed against: `ruff`, `mypy --strict`,
and the whole of `pytest`.

Tests that need a symlink skip themselves on a platform that will not make one,
rather than passing quietly. The suite says how many it skipped.

## Licence

Proprietary. Not for redistribution.
