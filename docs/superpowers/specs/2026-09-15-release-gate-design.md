# A release gate that runs the tunnel the way users break it

**Status:** approved design, not yet implemented
**Date:** 2026-09-15
**Scope of this document:** phase 1 of three. The target matrix covers all phases;
the architecture, thresholds, report and CI wiring below are phase 1.

## Problem

Nothing in the release pipeline runs a tunnel. `release.yml` builds and publishes;
`pr-checks.yml` runs JVM unit tests; the engine's own CI runs unit tests without the
`olcrtc_lean` tag the phones are built with, and a "Real E2E" matrix in which a cell
that fails to authenticate is *skipped* and counts as green. The bugs that reached
users in the last month were all of the kind this cannot see:

- the tunnel dying under a speed test (olcbox#15, #23, #25, #26): a load shape,
  only visible with bulk transfer and connects in flight at once;
- a Jitsi handshake lost when the server's bridge opens after the phone's (#22):
  an ordering the pairing never produces on one fast box;
- every WB Stream room failing with "engine not found" (#22, second half): a build
  tag that dropped an engine a provider needs — the lean build's contract was
  never executed in CI, so nothing could notice.

The harness that found and verified the fixes for all of these lives in a scratch
directory on one machine and is not in git. Each release is checked by hand on a
phone. There is no record to compare one release against the previous one.

## Goal

A gate in the release pipeline that runs the engine under the real relays and the
real fleet server with the load shapes that broke it, in the build the app ships,
fails the release on regressions, and leaves a report on every release that the
next one is compared against. The suite is a registry of named scenarios so every
fixed bug adds a cell and the count is visible.

## Non-goals (phase 1)

- Running the app itself. That is phase 2 (Linux, Windows, Android emulator on the
  built artifacts) and phase 3 (macOS proxy mode, iOS simulator, UI flows,
  screenshots). Their shape is fixed by the target matrix below so phase 1's report
  and cell naming already fit them.
- Real devices. iOS NetworkExtension memory and jetsam, carrier UDP policy and
  behaviour on real mobile networks stay manual until a device phase is decided.
- Replacing the engine's unit tests. The gate is additive.

## Target matrix (all phases)

Rows are platforms, columns are transports the app carries. ✓ = the full scenario
set through the real system tunnel; ◐ = the same scenarios without a system tunnel
(proxy inbound instead); the last two columns are per platform.

| Platform (phase) | olcRTC/Telemost (DE) | olcRTC/Jitsi, WB | VLESS Reality (DE) | Hysteria2 (DE) | XHTTP (DE) | UI flows | Memory |
|---|---|---|---|---|---|---|---|
| Engine, Linux (1) | ✓ | ✓ | – | – | – | – | heap/RSS, phone profile |
| Linux AppImage, TUN via sudo (2) | ✓ | ✓ | ✓ | ✓ | ✓ | Compose UI tests | RSS |
| Windows EXE, wintun (2) | ✓ | ✓ | ✓ | ✓ | ✓ | Compose UI tests | RSS |
| Android APK, emulator x86_64 (2) | ✓ | ✓ | ✓ | ✓ | ✓ | Maestro flows | dumpsys meminfo |
| macOS DMG, proxy mode (3) | ◐ | ◐ | ◐ | ◐ | ◐ | Compose UI tests | RSS |
| iOS simulator + Cores (3) | ◐ | ◐ | ◐ | ◐ | ◐ | XCUITest | Go limit, no jetsam |

Every cell is identified by `platform/provider/transport/client/scenario`, and the
report lists planned and executed cells; a planned cell that did not run is a
failure, never a skip.

## Phase 1 in one paragraph

A Go package `internal/gate` in the engine repository holds the scenario registry,
the two targets (a server started by the suite, or a link to a fleet node), the two
client flavours (the CLI's client path in the default build, `mobile.Runtime` in
the lean build), the load generators, a memory sampler, thresholds in one file, and
a JSON report writer. A companion `cmd/gate-report` renders the report as Markdown
and compares two reports. The engine's CI runs the suite against a local server on
every push; the app's `release.yml` runs it — for the pinned engine revision,
against the fleet's DE node through the partner API — before any build starts, and
attaches the report to the release.

## Architecture

### 1. Package layout (engine repository, `proofkit` branch)

```
internal/gate/
  gate.go          registry: Scenario{ID, Run}, Cell, plan(), zero-skip accounting
  target.go        Target: Local (child-process server) | Link (parsed olcrtc:// link)
  client_cli.go    client flavour "cli": internal/app/session (what cmd/olcrtc runs)
  client_mobile.go client flavour "mobile": mobile.Runtime (//go:build olcrtc_lean)
  load.go          HTTP load through the client's SOCKS: pulls, pushes, connect bursts
  dns.go           port-53 queries through the tunnel (the stream path)
  sampler.go       heap/RSS/goroutine samples at 1 s, peak and last
  thresholds.go    every number the verdicts use, per target
  report.go        gate-report.json v1 writer
  scenarios/       one file per scenario: s0_connect.go … s7_memory.go
  gate_test.go     go test entry: -olcrtc.gate, -olcrtc.gate-target, -olcrtc.gate-link,
                   -olcrtc.gate-report, -olcrtc.gate-rooms-*, -olcrtc.gate-dry
internal/link/     parser for olcrtc://<provider>?<transport>@<room>#<key>%<device>$<name>
cmd/gate-report/   render (markdown table, job summary) and compare (deltas, verdict)
internal/testhooks/ //go:build olcrtc_testhooks — env-driven delays the server honours
```

The suite is `go test` so it gets timeouts, `-run` selection, `-json` streaming and
the race detector for free; the report is written by `TestMain` from the cells the
tests recorded, so a panic or timeout in one cell still leaves a report with that
cell failed.

### 2. Targets

**Local.** The suite builds `cmd/olcrtc` with `-tags olcrtc_testhooks` and starts it
as a child process in `mode: srv` for each (provider, transport) pair, with a fresh
64-hex key and `debug: true`; its log is an artifact. Rooms: Jitsi — a random name
on an instance from `docs/jitsi.instances.yaml`, checked with one HTTPS request
first, the next instance on failure; Telemost and WB Stream — no provider in this
fork can create rooms (`auth.RoomCreators()` is empty), so each has a pool of
pre-made rooms passed as flags from secrets, and a run takes `pool[run_number mod
len]`. The workflow's `concurrency` group keeps two gates from sharing a room; a
different key per run keeps a leftover from a cancelled run from ever
handshaking. The server process is separate from the test process so the memory
sampler reads the client alone.

**Link.** An `olcrtc://` link, parsed by `internal/link` (the same grammar the app
imports), pointing at a fleet node. Only the client runs; the server is the fleet's.
The workflow obtains the link (section 8); the suite never talks to the platform.

**Test hooks.** `internal/testhooks` is compiled only with `olcrtc_testhooks` and
reads `OLCRTC_TEST_BRIDGE_DELAY`; the Jitsi engine calls `testhooks.BeforeBridgeOpen()`
which is a no-op in every other build. This is how S6 delays the server's bridge
without a knob in production binaries. The tag is never passed by a release build;
`pkg/olcrtc/client` gets a test asserting the hook package is absent from the
default and lean builds.

### 3. Clients

`cli` runs the same code path as `cmd/olcrtc` in `mode: cnc` (`internal/app/session`)
with a SOCKS listener on a random port. `mobile` runs `mobile.Runtime` exactly as
`OlcboxVpnService` and the iOS provider do: `SetProvider`, `SetTransport`,
`SetRoom`, `SetKey`, `SetDNS`, `SetSocksListenHost`, `SetSocksPort`, `SetDeviceID`,
`SetVP8Options(30, 32)`, `Start`, `WaitReady`, and `mobile.SetMemoryLimit(40 MiB)` with
`GOGC=10` in the environment — the phone's numbers. The mobile flavour exists only
under `olcrtc_lean`, so the suite binary is built twice; each cell records which
flavour ran it.

### 4. Scenarios

Parameters are the harness that reproduced the fixed bugs; pass criteria are what a
user would call "the tunnel works". Each scenario records its metrics into the cell.

| ID | Name | What it does | Pass |
|---|---|---|---|
| S0 | connect | connect, 5 MB pull, 5 MB push, close | both transfers complete; handshake ≤ 15 s; `session … opened` on both sides (local) |
| S1 | idle burst | 24 concurrent connects to a 1 KB resource, then 24 sequential | 100 % succeed; p95 connect ≤ 5 s |
| S2 | download saturation | 6 parallel 60 MB pulls; every 5 s a 1 KB connect on top (olcbox#23) | all 200; on-top connects 100 %, p95 ≤ 5 s; aggregate throughput ≥ floor |
| S3 | upload saturation | 4 parallel 30 MB pushes; connects on top as in S2 (olcbox#15) | as S2 |
| S4 | quiet after load | 60 s idle after S3 | control session alive: no `missed pong`, no `session closed`, no reconnect; one 1 KB pull succeeds at the end (olcbox#25) |
| S5 | resolver burst | 64 concurrent port-53 queries through the tunnel, twice | ≥ 63/64 answered each time within 5 s (the stream path, aac553b8) |
| S6 | late server bridge | Jitsi only, local only: server bridge delayed 3 s, then 8 s (olcbox#22) | handshake completes within 15 s in both |
| S7 | phone memory | sampler over S2–S4 with the mobile flavour | peak live heap ≤ 16 MB; peak RSS ≤ 45 MB; goroutines 60 s after load ≤ idle baseline + 20 (olcbox#24, #26) |

Plan per target: Local — providers {jitsi, telemost, wbstream} × transports
{datachannel, vp8channel, seichannel} × flavours {cli, mobile} for S0; S1–S5 and S7
for {jitsi/datachannel, telemost/vp8channel, wbstream/vp8channel} with the mobile
flavour; S6 for jitsi/datachannel with both flavours. Link (DE) — the mobile
flavour, S0–S5 and S7. Providers run in parallel, transports and scenarios in
sequence; the local plan fits in about ten minutes, the link plan in eight.

### 5. Load endpoints

Local target: an HTTP origin inside the test process (random port on loopback)
serving `/kb`, `/60mb` and `/sink` (`POST`, counts bytes); the server reaches it as
the client's exit. Link target: the fleet server cannot reach the runner, so pulls
come from `https://proofkit.org/gate/60mb.bin` (a static file on both APP servers,
one nginx `location /gate/`, cached by Cloudflare) and pushes go to
`https://speed.cloudflare.com/__up`. Both are flags with those defaults.

### 6. Thresholds

`internal/gate/thresholds.go` is the only place a number lives:

```go
var Local = Thresholds{ConnectP95: 5s, ThroughputDown: 2 Mbit/s, ThroughputUp: 2 Mbit/s,
    HeapPeak: 16 MiB, RSSPeak: 45 MiB, GoroutineGrowth: 20, ResolverAnswered: 63}
var Link  = Thresholds{ConnectP95: 5s, ThroughputDown: 0.8 Mbit/s, ThroughputUp: 1.5 Mbit/s, /* rest as Local */}
```

Absolute values are half of what the harness measured (Jitsi relay 5 Mbit/s; DE via
Telemost 1.4–1.6 Mbit/s down, 3 Mbit/s up; client heap 5–9 MB, RSS ~30 MB under the
phone profile) so runner variance does not fail a good build. Relative checks live in
`cmd/gate-report compare`: throughput −25 % or peak memory +25 % against the previous
release's report is a regression. For the first three releases relative regressions
are `warn`; the fourth makes them `fail`. Both modes appear in the report.

### 7. Report

`gate-report.json`, schema 1:

```json
{"schema": 1, "engine_commit": "…", "engine_ref": "proofkit", "app_version": "1.0.431",
 "target": "local|link", "runner": "ubuntu-24.04", "started_at": "…", "duration_s": 612,
 "planned": 41, "executed": 41, "passed": 40, "failed": 1,
 "cells": [{"id": "engine-linux/jitsi/datachannel/mobile/S2", "status": "pass",
   "metrics": {"throughput_down_bps": 4812000, "connect_p95_ms": 830, "heap_peak_bytes": 7340032},
   "thresholds": {"throughput_down_bps": 2000000, "connect_p95_ms": 5000},
   "failures": [], "log": "artifacts/jitsi-datachannel-srv.log"}]}
```

`cmd/gate-report render` writes the Markdown table (rows = cells, columns = key
metrics and verdict) for the job summary and the release body; `compare a.json
b.json` prints deltas per cell and exits non-zero on a regression at the configured
severity. Unit tests cover the parser, the threshold evaluator, render and compare.

### 8. CI wiring

**Engine `ci.yml`.** (a) Hygiene goes green: `go mod tidy` drops the 198 stale
`go.sum` lines. (b) The unit-test job runs twice, default and `-tags olcrtc_lean`;
the lean run is what would have caught the wbstream regression
(`internal/engine/builtin/registry_test.go`). (c) A `gate-local` job replaces the
"Real E2E" job: it runs the suite against the local target for both flavours, with
the room pools from repository secrets `GATE_TELEMOST_ROOMS` and `GATE_WBSTREAM_ROOMS`,
uploads `gate-report.json` and the server logs as artifacts, writes the table to
the job summary, `concurrency: gate-${{ github.ref }}`.

**App `release.yml`.** A `gate` job after `release_version` that every `build-*` job
`needs`:

1. checks out the engine at the revision `scripts/cores-pins.sh` pins (the 12-hex
   tail of `OLCRTC_VERSION`); the Android build switches from `OLCBOX_OLCRTC_REF:
   proofkit` (branch tip) to the same revision, so one commit is what every
   platform ships and what the gate tested;
2. runs the engine unit tests with `-tags olcrtc_lean` and the local plan (the
   pinned commit may be older than the last engine CI run);
3. obtains a DE link through the partner API (section 9) and runs the link plan;
4. downloads the previous release's `gate-report.json` from its assets and runs
   `compare`; uploads both reports and the Markdown as artifacts;
5. fails on any failed cell, on any planned cell that did not run, and on a
   regression at `fail` severity.

The publish job attaches `gate-report.json` and `gate-report.md` to the release
and appends the table under a "Gate" heading in the release body. The nightly
workflow runs the same job; a red nightly does not block anything but is visible.

### 9. Platform side: the gate's account

An admin creates partner `ci-gate` with terms `billing_exempt = true` and
`operator_credit_exempt = true`, a node grant on the DE origin, and stores its
token in the olcbox repository secret `GATE_PARTNER_TOKEN` with `GATE_DE_ORIGIN_ID`.
The workflow step is the documented partner flow: `POST /subscribers` with
`external_id = "ci-gate-<run_id>"`, `POST /subscribers/{id}/olcrtc` with
`origin_id`, then `GET /sub/{sub_token}/olcrtc` — plaintext `olcrtc://` lines unless
`?crypt=1` is asked, so no key material beyond the partner token is needed and the
session key rotates with every run. `DELETE /subscribers/{id}` at the end. Before
the first release with the gate, the anomaly and fraud detectors are checked for
how they treat sessions of a billing-exempt partner; if they count them, sessions
whose partner is billing-exempt are excluded from those statistics (one predicate
in the coordinator, with a test). The gate moves at most ~1 GB per run through a
node that serves real users; the DE origin's capacity slots are consumed like any
session, so the link step retries once after 60 s when the node reports no free
slot, then fails.

### 10. Failure semantics and artifacts

- Zero skip: the plan is enumerated before anything runs; every planned cell ends
  `pass` or `fail`. A provider that will not authenticate or a room that will not
  join is retried once after 30 s, then the cells that needed it fail with the
  reason; the rest of the plan still runs.
- Load scenarios are not retried: a flake under load is a finding, and the report
  shows the metric that missed so a human can judge.
- Artifacts on every run: the report, server logs per pair, client logs per cell,
  sampler CSV (`t,heap,rss,goroutines`) for S7 cells.
- Budget: `timeout-minutes: 30` on the job; a cell has its own deadline (S2/S3
  5 min, others 2 min) so one stuck cell cannot eat the run.

## Testing the gate itself

Unit tests: link parser (round-trip with the app's fixtures from
`LocationsRepositoryImplTest`), threshold evaluator (each rule with a passing and a
failing sample), report writer (schema, planned-vs-executed accounting), render
(golden Markdown), compare (each severity, a missing cell on either side). Integration:
`-olcrtc.gate-dry` prints the plan without running it and is asserted in CI; the
local plan against Jitsi runs in the engine's own CI, which is the gate testing
itself on every push. The suite is developed against the DE origin and the harness
numbers already on record, and its first release run is a calibration run.

## Rollout

1. Engine: package, scenarios, report, CI changes; three green pushes.
2. Platform: static file and nginx location on both APPs; the `ci-gate` partner;
   the detector exclusion if needed.
3. App: `release.yml` gate job, pinned-revision Android build, release assets and
   body; first release runs with relative checks at `warn`.
4. After three releases: relative checks at `fail`; phase 2 spec.

## Phases 2 and 3 (pointers, own specs)

Phase 2 runs the built artifacts: a headless mode in the desktop app
(`proofkit --connect <link> --proxy|--tun --scenario <id> --report <file>`, useful to
users for scripting and to us as the harness on Linux and Windows), and an
instrumented test on the Android emulator that imports a link through `proofkit://add`,
brings the VpnService up and runs the same scenarios through the device. Phase 3 adds
macOS proxy mode, an XCTest target linking Cores in the iOS simulator, UI flows
(Compose Multiplatform UI tests, Maestro, XCUITest) and per-screen screenshots
attached to each release.
