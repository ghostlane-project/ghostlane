# The release gate

Before any platform builds, `release.yml` runs the engine the app pins the way
users break it: the engine's unit tests, then the engine's release suite
(`internal/gate`) against the real relays, with a server the suite starts on the
runner. A red or cancelled gate builds nothing and publishes nothing. Every
release carries the gate's report, and the next release is compared with it.

The workflow is `.github/workflows/gate.yml`. `release.yml` calls it; it also
runs on its own every day, on a push that changes the pin or the gate, and by
hand.

> **Current state (2026-09-18).** The pin, `aaffe1e0`, predates the release
> gate: the engine revision has no `internal/gate`, and the gate's first job
> fails with a message that says so. Until the pin moves to an engine commit
> that carries the gate (see [Re-pinning](#re-pinning)), a release passes only
> with `gate: skip` and a reason.

## What runs

| Job | What | Secrets |
| --- | --- | --- |
| `resolve` | Resolves the engine revision to a full commit, checks that it carries `internal/gate` and `cmd/gate-report`, and decides the mode and the legs | none |
| `unit` (x2) | `go test -race ./...` of the engine, default build and lean build | none |
| `suite` (x3) | One leg per provider: `jitsi`, `telemost`, `wbstream` | that provider's own |
| `verdict` | Merges the legs, compares with the previous release, renders the markdown, leak-checks it, uploads `Ghostlane-gate-report`, decides | none |

Each suite leg runs the same steps:

1. A dry run of each flavour writes its plan, the cells it owes. No secret goes
   into the dry run.
2. The leg's secrets are checked. A missing or malformed one fails the leg. The
   message names the secret and the entry's position, never a value.
3. The **cli** flavour runs in a `go test` process built with no tags, which is
   what the desktop ships (`./cmd/olcrtc`, no tags).
4. The **mobile** flavour runs in a separate process built with `-tags
   olcrtc_lean`, which is what Android and iOS ship (`mobile.Runtime` with the
   phone's memory limits). It runs even when the cli flavour was red. The
   phone's memory settings apply to this process only (engine plan A8).
5. Everything under `gate-artifacts/`, which holds the leg's directory and is
   the upload's root, is scrubbed and then verified clean. Only then is it
   uploaded.

The two flavours of a leg run one after the other in the same room. That is why
one room per provider is enough.

The target is **local**: the suite builds `cmd/olcrtc` and starts it as the
server for each provider and transport pair, then points the client at it
through the real relay. The load scenarios (S0 to S7), their thresholds and the
report format belong to the engine. See `docs/gate.md` in
`romanpodpriatov/olcrtc`, and `internal/gate/thresholds.go` for the numbers.

## When it runs

| Mode | Trigger | Notes |
| --- | --- | --- |
| `release` | `release.yml`, job `gate`, before every build | Every build job and `publish-nightly` need it |
| `scheduled` | Daily, 04:17 UTC | A canary. It blocks nothing. Named so to avoid `nightly`, which is `release.yml`'s rolling release tag |
| `pin` | A push to any branch that changes `scripts/cores-pins.sh`, `scripts/olcrtc-pin.sh`, `scripts/gate-*` or `gate.yml` | Runs once per push. Tag pushes never trigger it. `pr-checks.yml` does not run the gate |
| `manual` | Actions → Release gate → Run workflow | `engine_ref` gates a candidate commit, branch or tag instead of the pin. `providers` and `transports` narrow the run, which is then labelled limited and is never a release gate |

`transports` is passed to every chosen provider. Pick transports each of them
carries: Telemost has `vp8channel` and `videochannel`; WB Stream has
`vp8channel`, `videochannel` and `seichannel`; Jitsi has all four. Otherwise the
engine refuses the plan.

## Reading a result

- **The run's summary.** The `Verdict` job writes a header with the mode, the
  app version, the engine commit (and whether it is the pin and on `proofkit`),
  and one row per leg and flavour. Below the header come the engine's table
  (`gate-report render`) and the comparison. Each leg adds one line per flavour.
- **A release.** Its notes have a `### Gate` section with the same markdown.
  Its assets include `gate-report.json` and `gate-report.md`.
- **Artifacts.**
  - `Ghostlane-gate-report` holds the merged report and markdown, with default
    retention. Its name matches `Ghostlane-*`, which is how `publish-nightly`
    picks it up.
  - `gate-leg-<provider>` holds `<provider>/cli/` and `<provider>/mobile/`:
    each flavour's plan, report and scrubbed logs, and the memory samples. It
    is kept for 14 days and is never a release asset. `Verdict` downloads
    every leg's artifact merged into one directory, so a run with one leg
    lays out like a run with three.

A cell is `platform/provider/transport/client/scenario`, for example
`engine-linux/telemost/vp8channel/mobile/S2`. Nothing in a cell name, a report
or a log names a room.

### What makes it red

The last step of `Verdict` lists every reason that applies:

- An engine unit test failed, or a suite leg failed or was cancelled.
- A cell failed.
- A planned cell did not run. The engine writes its report when the test
  binary exits, so a `go test -timeout` or a crash loses it. The leg's plan
  still names the lost cells, and the merge fails each of them as `did not run:
  the <provider>/<client> run wrote no report`. A flavour that left neither a
  plan nor a report fails as one marker cell, `engine-linux/<provider>/*/<client>/*`.
- A flavour's report was rejected: another schema, another engine commit,
  another target, or a cell listed twice or belonging to another leg.
- The merge, the markdown, the leak check or the upload did not succeed.
- A regression against the previous release, at severity `fail`. Today the
  severity is `warn` (see below), so this never applies.

Nothing is skipped. A leg with a missing secret fails, and its planned cells
fail as `did not run`.

## Compared with the previous release

The baseline is the newest earlier release `vX.Y.Z` with a `gate-report.json`
asset that is:

- schema 1;
- for the local target;
- not a break-glass stub;
- planned at least one cell.

The release being made is never its own baseline. This covers a re-run after a
partial publish. At most five candidates are tried
(`scripts/gate-previous-report.sh`).

- **Local to local only.** Cell ids carry no target, so a local cell and a
  link cell with the same name would be compared as if they were one cell.
- **Severity `warn`** while the gate runs the local target only. A regression
  (throughput down or memory up by more than 25 %, or a pass turned into a
  fail) is printed in the table and does not fail the gate. The value is the
  literal `compare_severity: warn` in `release.yml`'s `gate` job. A
  reusable-workflow call cannot read `env`. Consider `fail` once LINK compares
  a fleet node release to release.
- **First gated release.** It reads "Compared with: nothing", which is not a
  failure. That release is the calibration run.

## Skipping (break-glass)

Dispatch `release.yml` with `gate: skip` and a `gate_reason`. The reason is
required, and the gate fails without one.

- `resolve` still resolves the engine revision. It does not require the gate at
  that revision, so a skip works while the pin predates the gate.
- `unit` and `suite` do not run.
- `Verdict` writes a skipped report and markdown and adds a warning to the run.
  The skipped report says "Gate skipped by request: `<reason>`. Engine
  `<commit>` was not gated".
- The gate job passes, so the builds run. The release's `### Gate` section
  shows that text, and `publish-nightly` repeats the warning.
- A skipped report is never a baseline.
- A partial platform run (`android`, `ios`) with `gate: skip` uploads to Play
  internal testing and TestFlight ungated.

Use it for emergencies, not for a red cell you dislike: that cell is what a user
would have hit.

## Secrets

| Secret | Leg | Required | What |
| --- | --- | --- | --- |
| `GATE_TELEMOST_ROOMS` | telemost | yes | Telemost room ids or links. The gate joins the **first** |
| `GATE_WBSTREAM_ROOMS` | wbstream | yes | WB Stream room ids. A room link works too: the engine keeps its last path segment. The gate joins the **first** |
| `GATE_WBSTREAM_TOKEN` | wbstream | yes | A WB Stream account access token. WB refuses a guest as the first participant of an idle room (`403 guests cannot create rooms`), so the suite's server signs in with it. The client stays a guest, as the app is |
| `GATE_JITSI_HOSTS` | jitsi | no | Bare Jitsi host names, for example `meet.example.org`: no scheme, no port, no path. When set, they replace the engine's `docs/jitsi.instances.yaml` |

Rules:

- **Format.** Commas or newlines separate entries, and whitespace is trimmed.
  Every room id and host must be at least 6 characters, and the token at least
  16. A mask shorter than that shreds every log line it matches, so a shorter
  value fails the check instead of running unmasked. A Jitsi host with a port
  fails the check too: a Go error prints the host without its port
  (`lookup <host>`), and only the entry as given is masked and scrubbed.
- **Which entry is used.** A leg always uses the first entry of its pool. Later
  entries are spares. To replace a room that stopped working, move a spare to
  the front. The engine picks from its pool by run number, which would differ
  between runs, so `scripts/gate-run.sh` hands it the first entry only.
- **Where they reach.** Only the steps that need them, through step `env`.
  Never job env, never a script's text, never argv: `gate-run.sh` exports them
  to `go test` under the engine's names. Each leg gets only its own provider's
  secrets, and the token reaches the wbstream leg only. `resolve`, `unit` and
  `verdict` hold none.
- **Masking.** In each leg, the first step that holds the secrets registers
  every entry, its query-less form, its last path segment, each Jitsi host and
  the token with `::add-mask::` (`scripts/gate-mask.sh`). GitHub masks only a
  secret's whole value, and a pool is a whole list.
- **What is uploaded.** Everything a leg uploads is scrubbed first
  (`scripts/gate-scrub.py`):
  - the secrets become `<room>`, `<jitsi-host>` and `<token>`, in raw,
    JSON-escaped and percent-encoded forms;
  - 64-hex keys become `<key>`;
  - the suite's own `gate-<12 hex>` room and channel names become `gate-<room>`;
  - JSON Web Tokens (a relay's session credentials) become `<jwt>`;
  - files other than `.json`, `.md`, `.log`, `.csv` and `.txt` are deleted.

  The directory is then re-read, and any residue fails the upload. The merged
  report is checked again before it becomes an artifact, a summary or a
  release asset. The repository is public, and so is every artifact.

Set them from standard input, never on a command line:

    gh secret set GATE_TELEMOST_ROOMS --repo romanpodpriatov/ghostlane
    gh secret set GATE_WBSTREAM_ROOMS --repo romanpodpriatov/ghostlane
    gh secret set GATE_WBSTREAM_TOKEN --repo romanpodpriatov/ghostlane
    gh secret set GATE_JITSI_HOSTS    --repo romanpodpriatov/ghostlane   # optional

### Disjoint from the engine repository

The engine's own CI (`romanpodpriatov/olcrtc`) runs the same suite with its own
`GATE_*` secrets. A concurrency group cannot span two repositories, so the two
sets of pools must not share a room.

- **Telemost.** Separate rooms exist. Keep them separate.
- **WB Stream.** Only one room exists today, and both repositories use it until
  a second one is made. When an engine CI run and an app gate run overlap, two
  servers signed in with the same WB account sit in one room, and they can knock
  each other out. The engine gives every pair its own channel id (engine plan
  A4), which keeps their frames apart but not the account's sessions. Until
  this repository has its own WB room, check whether the engine's CI was
  running at the same time before believing a red wbstream leg, and re-run it.

### Rooms rot

Pre-made Telemost and WB Stream rooms disappear, and a WB room also has to
exist when the first participant joins. The symptom is a leg whose cells all
fail at the server's join (a 403 or 404 from the provider in the reason). The
fix is a new first entry. The daily scheduled run is the canary for this.

## Concurrency

Every suite leg is in the job-level group `ghostlane-gate-<provider>`, with
`cancel-in-progress: false`. At most one run of this repository is in a
provider's room at a time: releases, scheduled, pin and manual runs alike. A
running leg is never cancelled for a newer one.

**Caveat.** GitHub keeps one pending job per group. A third run arriving while
one leg runs and another waits cancels the waiting one. If the waiting one
belonged to a release, that release's gate fails as cancelled and nothing is
published. Re-run it. The scheduled run starts at 04:17 UTC, so avoid starting
releases right then.

There is no workflow-level group in `gate.yml`, because a called workflow's
group would contend with its caller's. `release.yml` keeps its own
(`release-<ref>`, cancel in progress): a newer release dispatch cancels the
older one, gate included, which frees the provider groups.

The legs start as soon as `resolve` is done, alongside `unit`. A typical run
takes about 15 to 20 minutes, and every build waits for it. The ceilings are:

| Scope | Minutes |
| --- | --- |
| A leg's plan step (both dry runs) | 10 |
| A leg's cli step | 25 |
| A leg's mobile step | 35 |
| A leg | 80 |
| `go test -timeout`, cli / mobile | 20 / 30 (`scripts/gate-run.sh`) |

These are calibration values. Revisit them after the first runs.

## Re-pinning

`OLCRTC_VERSION` in `scripts/cores-pins.sh` is the engine of **every** platform,
not only the iOS Cores:

- Windows, macOS and Linux build `cmd/olcrtc` from it.
- Android builds the AAR from it.
- iOS builds the app's `OlcRtcMobile` from the checkout, and the extension's
  Cores come from the same pin through the Go module proxy.
- The gate tests it.

`release_version` resolves it once with `scripts/olcrtc-pin.sh`. The 12-hex tail
becomes the full commit, and the pseudo-version's timestamp must equal the
commit's time. All five build jobs then check that commit out.

Android and the desktop used to follow the tip of `proofkit`. That is over: an
engine fix now reaches users only through a re-pin.

1. Dispatch **Release gate** with `engine_ref=<candidate>`. This gates the
   candidate before anything is built from it or paid for on a 10x runner.
2. When it is green, set `OLCRTC_VERSION` to the candidate's pseudo-version,
   `v0.0.0-<commit time, UTC, yyyymmddhhmmss>-<first 12 hex>`. Bump
   `CORES_BUILD`, and copy `OLCRTC_VERSION` into `ios-frameworks.yml`'s `env`,
   which checks itself against `cores-pins.sh`.
3. Run **iOS Frameworks**, so the Cores for the new tag exist.
4. Push. The push runs the gate in `pin` mode against the new pin.
5. Release.

Pin a commit on `proofkit`. GitHub keeps serving a commit no branch reaches only
until it is garbage-collected, and then every checkout of it fails. The iOS
Cores survive through the module proxy; nothing else does. `olcrtc-pin.sh` warns
when the pin is not on `proofkit`, and the release notes say so.

The first re-pin after this change has to reach an engine commit that carries
the gate: `internal/gate` and `cmd/gate-report`, with the flags of the engine
plan's Task 13 and amendments A1 to A9. `resolve` refuses anything older.

## A red scheduled run

Nothing is blocked: a release runs its own gate. The run is red, and GitHub
notifies whoever last edited the cron line. The pin did not change, so
something outside the app did:

- **A room rotted.** Replace the entry the failing leg names (see above).
- **Public Jitsi instances died.** Set `GATE_JITSI_HOSTS`.
- **A relay changed.** If a provider changed its API or its limits, the
  **shipped** app is probably broken for users right now. Treat it as an
  incident, not as a flaky test.

The next release will be red for the same reason. `gate: skip` is the way
through while it is fixed.

## The engine contract

`scripts/gate-run.sh` is the only place that spells the engine's names. The app
depends on:

- **Revision.** `internal/gate` and `cmd/gate-report` exist at the gated
  revision.
- **Suite.** `go test ./internal/gate -run '^TestGate$' -v`.
- **Flags.** `-olcrtc.gate`, `-olcrtc.gate-target=local`,
  `-olcrtc.gate-dir=<absolute>`, `-olcrtc.gate-providers=<one provider>`,
  `-olcrtc.gate-clients=<cli|mobile>`, `-olcrtc.gate-dry`, and
  `-olcrtc.gate-transports=<list>` (manual runs only).
- **Builds.** cli has no tags. mobile has `-tags olcrtc_lean`, and asking for
  it in any other build is a plan error.
- **Environment.** `OLCRTC_GATE_TELEMOST_ROOMS`, `OLCRTC_GATE_WBSTREAM_ROOMS`,
  `OLCRTC_GATE_WBSTREAM_TOKEN` and `OLCRTC_GATE_JITSI_HOSTS`, never argv. The
  engine's `-olcrtc.gate-telemost-rooms`, `-olcrtc.gate-wbstream-rooms` and
  `-olcrtc.gate-jitsi-hosts` flags are deliberately unused. Report metadata goes
  in `OLCRTC_GATE_ENGINE_COMMIT`, `OLCRTC_GATE_ENGINE_REF` and
  `OLCRTC_GATE_APP_VERSION`.
- **Report.** `<gate-dir>/gate-report.json`, schema 1, written when the test
  binary exits.
- **Plan.** The dry run prints one cell id per line on stdout.
- **Render and compare.** `go run ./cmd/gate-report render <report>` and
  `go run ./cmd/gate-report compare -severity <warn|fail> <prev> <cur>`. The
  flags go before the files, and exit 2 means a regression at `fail`.

When the engine's gate lands, compare its `internal/gate/gate_test.go` and
`cmd/gate-report` with this list and change `gate-run.sh` only.

## Where LINK slots in

The spec's second target runs the mobile flavour against a fleet node through an
`olcrtc://` link from the partner API. It is left out until the platform side
(the `ci-gate` partner and the load files on both APPs) is in production.
Nothing in the workflows refers to it yet. When it comes:

- `resolve` adds a fourth leg, `{provider: link-de}`, in its own group
  `ghostlane-gate-link`, when the partner secrets exist.
- `gate.yml`'s `workflow_call` secrets and `release.yml`'s mapping gain the
  partner key and the DE origin id. Settle the name first: the spec says
  `GATE_PARTNER_TOKEN`, Plan B says `GATE_PARTNER_KEY`.
- The leg runs:
  1. `scripts/gate-link.sh`, which masks the link, room, slug and key;
  2. `gate-run.sh`, extended with a `link` mode that passes
     `-olcrtc.gate-target=link -olcrtc.gate-clients=mobile` and the link in
     `OLCRTC_GATE_LINK`;
  3. an always-run cleanup of the subscriber.

  It uploads the report only. Link logs are never written.
- `verdict` writes a separate `gate-report-link.json` and `.md`. Cell ids carry
  no target, so link cells would collide with local telemost cells.
  `gate-previous-report.sh` learns to fetch that asset for a link-to-link
  comparison. The release attaches both reports, and its notes get two tables.
- Only then is `compare_severity: fail` worth considering.

## Files

| File | Role |
| --- | --- |
| `.github/workflows/gate.yml` | The gate: resolve, unit, suite, verdict |
| `.github/workflows/release.yml` | Calls it (`gate` job); builds and publish need it; the pin as a commit; the report in the notes and assets |
| `.github/workflows/pr-checks.yml` | Builds against the pinned commit and Go; runs the scripts' tests |
| `scripts/olcrtc-pin.sh` | The pin (or `--ref`) to a full commit, with checks |
| `scripts/gate-resolve.sh` | Mode, revision, legs; `check <provider>` for a leg's secrets |
| `scripts/gate-run.sh` | Every engine name; unit, plan, run, render, compare |
| `scripts/gate-mask.sh` | `::add-mask::` for every secret value |
| `scripts/gate-scrub.py` | Scrub and verify what is uploaded |
| `scripts/gate-merge-reports.py` | Merge, header, skipped stub, decide |
| `scripts/gate-previous-report.sh` | The baseline release's report |
| `scripts/gate-api.sh` | Read-only GitHub API calls (curl) |
| `scripts/test_gate_scripts.py` | Offline tests for all of the above |
