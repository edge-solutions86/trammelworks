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

The on disk formats are fixed: spool segments, the state files beside them and
the SQLite archive (schema version 2, upgrading a version 1 file on first open)
are what sites already hold, so an upgraded box keeps reading what it wrote.
Batch ids, record keys and batch signatures are computed over canonical JSON
whose key order, escaping and number text never change, because the collector
recomputes them.

## Tech stack

- Java 21, built with Maven (wrapper included)
- picocli for the command line
- SnakeYAML (safe constructor) for the site config
- SQLite through `org.xerial:sqlite-jdbc` for the local archive
- the JDK's own HTTP server for the read only query service, and `java.net.http`
  for the uplink
- JUnit 5 and AssertJ for tests, Spotless (palantir format) and JaCoCo (80% line
  gate) in the build

## Build, test, run

```console
$ ./mvnw -B verify                      # format check, compile, every test, coverage gate
$ ./mvnw -B -q spotless:apply           # reformat before committing
$ ./mvnw -B test -Dtest='Query*Test'    # one area while working on it
$ java -jar target/trammelworks-1.0.0-cli.jar --help
```

`verify` builds `target/trammelworks-1.0.0-cli.jar`, a single jar with every
dependency inside. Install it as `/usr/local/bin/stanchion` behind a one line
wrapper (`exec java -jar /opt/stanchion/trammelworks-1.0.0-cli.jar "$@"`), which
is the path the generated systemd units name.

The suite is the specification. Property tests draw their examples from fixed
seeds, so a failure names the same input on every run and every machine. A test
class that leaves anything under `/var/lib/stanchion` fails, because that is
where a commissioned box keeps its state.

## Getting a site running

```console
$ stanchion --config /etc/stanchion/site.yaml config --check
ok US-TX-0417

$ stanchion --config /etc/stanchion/site.yaml doctor
checking US-TX-0417
NOTE build              stanchion 1.0.0, spool format 1
NOTE log root           following 4 files: loyalty-sync, payment-kernel, price-sign, pump-bridge
NOTE redaction          card numbers are masked, 2 site rules
NOTE signing            signing as tx-0417-2025
NOTE space              spool 512MiB plus archive 2GiB is 2560MiB
...

$ stanchion --config /etc/stanchion/site.yaml units --serve --to /tmp/units
```

`examples/site.yaml` is a commissioned site written out in full. It is loaded
and checked by the test suite, so it cannot drift away from what this build
accepts. Environment variables named `STANCHION_SITE`, `STANCHION_LOG_ROOT`,
`STANCHION_POLL_INTERVAL`, `STANCHION_SPOOL_DIR`, `STANCHION_SPOOL_BUDGET`,
`STANCHION_ENDPOINT`, `STANCHION_MONTHLY_ALLOWANCE` and `STANCHION_BATCH`
override the file; any other `STANCHION_` variable is refused.

## The commands

Every command takes `--config PATH` (default `/etc/stanchion/site.yaml`) and
`-v` / `--verbose` before the command name. An expected failure prints one line
carrying its stable error code, such as `stanchion.config: config file not found
(/etc/stanchion/site.yaml)`, and exits 1, or 2 when the config or the command
line itself is unusable.

| command | what it is for |
| --- | --- |
| `config` | print the config as it was understood; `--check` only validates |
| `version` | the version; `--full` or `--json` for every number that identifies the build |
| `run` | collect and ship, `--cycles` times or `--forever`. What the unit file starts |
| `ship` | the uplink half on its own, for watching a backlog drain |
| `discover` | list the container log files this site would follow, and what it skipped |
| `tail` | run the readers for a few passes and print what comes out |
| `backfill` | read the rotated files the tailer never saw. Never automatic |
| `query` | search the local archive with a filter expression, paged by cursor |
| `count` | the shape of what happened, grouped `--by` a field or `--every` a bucket |
| `report` | write records as text, jsonl, csv or markdown; exits 4 when nothing matched |
| `summary` | the nightly summary, as text or `--json` |
| `hold` | `list`, `place` and `lift` the legal holds that stop retention |
| `sweep` | apply retention; `--dry-run` says what would go |
| `housekeep` | retention, then the size cap, then the vacuum |
| `spool` | what is waiting and which class it is in; `--plan`, `--verify`, `--segments` |
| `clock` | boot sessions, corrections and the crystal's drift rate |
| `redact` | run the redaction pass over a file or standard input; exits 3 when it removed something |
| `serve` | the read only query service, loopback by default |
| `verify` | check a directory of batches against a site's signing key; exits 3 on a refusal |
| `units` | generate the systemd units this site should run |
| `doctor` | check that this site will still be working next week; exits 2 on warnings, 1 on failures |

A filter is typed by hand and needs no quotes around a dashed value:

```console
$ stanchion query 'unit = payment-kernel and message ~ declined since 2h'
$ stanchion count --by unit 'class = payment and not message ~ heartbeat'
$ stanchion query 'unit in (pump-bridge, tank-gauge) and stream = stderr limit 50'
```

## The query service

`stanchion serve` answers JSON for every route, errors included, and refuses
anything but `GET` and `HEAD`. It binds `127.0.0.1:9787` unless told otherwise;
binding off loopback needs `--any-interface` and a `--token`, which callers send
as `Authorization: Bearer <token>`.

| route | answers |
| --- | --- |
| `/`, `/health` | the site, `ok`, and the time the service believes it is |
| `/summary` | the nightly summary built from what is on disk |
| `/records?filter=...&limit=...&after=...` | a page of archived records, at most 500, with `next` when there may be more |
| `/units` | the units the archive has heard from, and records per class |
| `/metrics` | gauges read off the spool, the meter and the archive, with a plain `name value` text form |

## Layout

```
src/main/java/io/github/rkaushikdev/trammelworks/
  (root)     site config and file, the site pipeline, housekeeping, doctor,
             units, replay, simulation, records, timestamps, JSON, telemetry
  ingest/    reading container logs: CRI, docker json-file, journald export
  clockfix/  boot sessions, drift estimation, reconciliation
  redact/    the fail closed redaction pass
  spool/     framing, segments, the disk budget, priority eviction
  ship/      batching, the meter, backoff, watermarks, signing, compaction
  archive/   the local SQLite store, retention, holds, space
  query/     the filter language: lexer, parser, planner, evaluator, counts
  report/    the nightly summary and the render formats
  service/   the read only HTTP routes and the server that hosts them
  cli/       the stanchion command and its subcommands
src/test/java/...       one test class per area, same packages
src/test/resources/     fixtures, including a schema version 1 archive
examples/site.yaml      a commissioned site, checked by the suite
```

## Licence

Proprietary. Not for redistribution.
