# The Rust core on every platform

`rust/all` is the branch on which Ghostlane moves from its Go cores (sing-box, Xray-core and
the olcRTC engine) to one Rust core on every platform: Android, iOS, macOS, Linux, the CLI
and Windows. `main` stays the branch every published build is made from until this one is
merged into it.

## Where the core lives

The core is [ghostlane-project/xray-rust](https://github.com/ghostlane-project/xray-rust), a
fork of xray-rust that also holds the olcRTC client in Rust. The app names exactly one commit
of it, the pin, in `scripts/ghostlane-core-pin.sh`. On the fork, `ghostlane` is the branch the
shipped iPhone tunnel is built from, and `p6/integ` collects the changes this branch needs.
The pin moves by a pull request that changes the pin and what `scripts/pin-move.sh`
regenerates from it, and nothing else (see "A pin move" below).

## How work joins this branch

- One task, one branch `rust/t/<id>-<slug>`, one pull request into `rust/all`.
- The failing test comes first: it is pushed and seen red in CI before the change that makes
  it green.
- A pull request is merged with a merge commit once PR Checks are green and it has been
  reviewed. A pushed branch is never rebased or force-pushed.
- `main` is merged into `rust/all` after each release from `main`, never into a task branch.

## What this branch must not do

No build from `rust/**` is signed, uploaded to a store or published, and no push to it starts
a release gate run against live rooms. Until the release guard for that is merged here,
`release.yml` is not dispatched from this branch.

## When it is done

Nothing a published build ships contains a Go program or library: `scripts/check-no-go.sh`
reads every shipped file, at every nesting, and fails on one. Each feature of today's builds
has a row under `docs/rust-parity/` that names the test proving it on the Rust core.

## A pin move

The pin is the one line `GHOSTLANE_CORE_COMMIT=<40 hex>` in `scripts/ghostlane-core-pin.sh`. Check
K7 holds that no second copy of it exists. The pin is moved only to a commit of the fork on
`p6/integ` whose `ghostlane-p6.yml` run is green and which `core-build.yml`, dispatched with
`pin=<sha>`, has built for every target.

    scripts/pin-move.sh <40-hex commit of the core>

The script rewrites the pin line and then runs every executable file of `scripts/pin-move.d/`
in name order, with `GHOSTLANE_CORE_COMMIT` set to the new commit. A step that fails stops the
move. A task that has something to regenerate adds one file there and never edits the script.
The steps today:

- `events-hash` copies `crates/ghostlane-engine/tests/fixtures/events.jsonl` of the core at the
  new commit into the app's tests and writes its sha256 beside it. It prints `events: unchanged`
  or `events: changed`; on `changed` the diff of the copy shows what `HostEventsTest` and the
  app's parser have to learn. `events-hash --check <commit>` writes nothing and fails unless the
  copy is the fixture at that commit byte for byte; `rust-desktop.yml` runs it at the pin.
- `notices` rewrites the generated block of `THIRD_PARTY_NOTICES.md` from the app's locks and
  the core's `Cargo.lock` at the new commit, so that K10 stays green. A crate with no licence in
  `scripts/notices-licences.json` stops the move: run `scripts/notices.py fill --pin <commit>`,
  read what it added, and run the move again.
- `ios-size` prints the iOS closure class of the move (`scripts/ios-closure.sh class`): `same`
  means the extension links the same crate sources, so the iOS set (K6) need not be re-run;
  `changed` means K6 must be green. It then checks the size of the iPhone library. The size
  cannot be read without a build, so it is taken from the `SIZE aarch64-apple-ios` line of the
  Core build's Apple job at the new commit, given as `IOS_LIB_BYTES=<bytes>` or
  `IOS_CORE_REPORT=<path of report.txt>`. It is compared with the last line of
  `ios-size.record`. A growth over `IOS_SIZE_MAX_GROWTH` (131072 bytes by default) fails the
  step; whoever moves the pin may raise the limit for that one move and says why in the pull
  request. A step that passes appends its line to the record (`ios-size.record` is the record,
  not a step).

The pull request `rust/t/pin-<n>` changes the pin file and what these steps write, nothing
else. Every workflow that reads the pin names the pin file in its paths, so the pull request
itself shows the core's CI at the commit (`rust-core.yml`), K5, and every platform workflow.
When the iOS class is `changed`, the iOS set K6 must be green too. When `olc/` changed, K4 runs
on the fakes. A merged pin move does not touch the fork's `ghostlane` branch: that branch is
fast-forwarded once, to the final pin, and a run that signs refuses a pin that is not an
ancestor of it (see the next section).

A task that needs a fork change depends on the pin move, never on a fork branch. A store
hotfix is a fork topic from main's pin; before main pins it, `rust-core.yml` and `rust-ios.yml`
are dispatched from `rust/all` with `pin=<fix>` and must be green, since the fork's own CI does
not run on its branches.

## Dry runs and `signed_head`

`scripts/release-guard.sh decide` says in one place whether a `release.yml` run may sign,
upload to a store and publish. The rule:

- `dry_run` ticked: the run is dry, anywhere, whatever else is set.
- On `main`: the run signs.
- Off `main`: the run is dry unless `signed_head` is the full 40-hex commit being built. A
  `signed_head` that names another commit, or is not 40 lower-case hex digits, fails the run
  before anything is built.

A dry run builds every chosen platform unsigned (or with a throw-away key where the platform
needs one to install), uploads to no store, publishes nothing, reads no signing secret, and has
the gate write its skipped report with one fixed reason instead of joining a room. A run that
signs also runs `release-guard.sh pin`: it builds only a core commit that the fork's
`ghostlane` branch holds, and fails when the pin is not an ancestor of it or when GitHub will
not say. A dry run prints the same finding and goes on. `release-dry.yml` holds all of this.

A dry run is how the release's artefacts are checked before a release: the integrator
dispatches `release.yml` with `dry_run` and the platforms wanted, and K1 runs inside it on the
finished assets. `no-go.yml` takes the run's id to read the artifacts of a dry run that K1
inside `release.yml` did not read (the IPA and the AAB).

## The checks

Each check has a number, a runner and a workflow or script that runs it. A red check is read
from the job's summary first; the job log is for what the summary does not say.

- **K1, no Go in what ships.** `scripts/check-no-go.sh`, run by `no-go.yml` and inside
  `release.yml`'s jobs on every finished asset, dry runs too. See "How to read a red K1".
- **K2, a real tun in a namespace.** Scripts that run as root in network namespaces on
  `ubuntu-latest`: the desktop's `scripts/desktop-checks/` (`linux-tun.sh` and others, run by
  `rust-desktop.yml`) and the CLI's `scripts/cli-checks/` (`netns.sh`, run by
  `rust-cli-netns.yml`). A red means TCP, UDP, names, the IPv6 refusal, the engine's way out or a
  room behind its front is lost, or a rule is left after `kill -9`. The leftover rules are in
  the script's own output.
- **K3, the emulator matrix.** Job `e2e` of `rust-core-android.yml`: x86_64 emulators of API 23,
  24, 29, 34 and 35, and 35 with 16 KiB pages, one leg each, on ubuntu with KVM. Every class of
  the `androidTestRustcore` package must run and pass, and the kill cell (SIGKILL to the app's
  process, the session down with it and back) must pass. Job `k3` puts every class and the kill
  on every leg in one table; a class that failed or did not run is a red row there.
- **K4, the gate's Rust leg.** The engine's suite with `ghostlane-engine` as the client against
  the Go server at the exits' pin, on fakes on ubuntu. In this repository the Jitsi leg is
  `rust-jitsi.yml` (scenarios S0 to S7, the TLS leg and the BOSH leg, with no live room). Live
  rooms are run only by the owner's word.
- **K5, lines against Xray.** Job `lines` of `rust-core.yml`: for every file under
  `scripts/rust-core-interop/lines/`, the builder's own dump of the link is the client against
  the official Xray 26.4.25, 26.7.11 and 26.11.9; a 2 MiB download and a 1 MiB upload must
  arrive whole, a wrong-key line and every no-flag insecure line must fail, and a mutated dump
  must go red. The same lines run on a Mac in `rust-ios.yml` and through the CLI's engine child
  in `rust-cli-engine.yml`. A red names the cell (the line and the Xray version).
- **K6, the iOS set.** `rust-ios.yml`: the extension's engines on a Mac host against the darwin
  slice of the core at the pin (jobs `harness`, `mac`, `app`), the unsigned app build, and the
  `nm` and Mach-O reads of the extension and the app; the fork's guards at the pin run in
  `rust-core.yml` (job `fork-guards`). On ubuntu and `macos-15`. It is required when the iOS
  closure class of a pin move is `changed`.
- **K7, pins.** `scripts/p6-tests/pin.sh`, run by the `gate-scripts` job of `pr-checks.yml`
  with every script of `scripts/p6-tests/`. Fails when a second copy of the core pin exists,
  when a workflow that reads the pin does not name the pin file in each of its `paths:` lists or
  has no `pin` input, or when a workflow that must build the pin and nothing else has one. The
  plan also puts into K7 the agreement of `OLC_PIN`, the app's engine pin and the exits' pin;
  `scripts/p6-tests/pin.sh` does not read that part: `scripts/pins-agree.sh` does (run by
  `scripts/p6-tests/pins-agree.sh` in the same job), reading `OLC_PIN` of the core at the pin and
  comparing it with `OLCRTC_VERSION` in `scripts/cores-pins.sh` (and with a `cli/go.mod` or `cli/go.sum`, should one exist: none does since C7).
- **K8, the Mac helper.** The `mac-helper` job of `pr-checks.yml` runs every script of
  `scripts/mac-checks/` as root on `macos-latest` and `macos-15-intel`: utun, routes,
  exclusions, the pf group, and the clean-up after SIGKILL; LAN, the interface watch, probes
  and a room's rules.
- **K9, the Windows tun.** A fetch through Wintun, the engine's way out, the DNS guard and the
  sweep after `taskkill`, on `windows-latest` as administrator. The Windows system pieces
  (adapter counters, the system-proxy registry values, the LAN firewall rule) are
  `rust-windows-sys.yml` running `scripts/desktop-checks/windows-sys.ps1`; the Windows leg of
  `rust-desktop.yml` runs the same script and the bundle check, which fails a bundle with no
  engine; that leg and the two K9 workflows are required. The Windows job of `release.yml`
  builds the engine at the pin, hev and Wintun, signs the PEs it builds (`WINDOWS_PES_WE_BUILD`,
  `scripts/windows-sign.ps1`) before they are packed, holds the finished jar to that list, and
  runs K1 on the exe, the msi and the zip. `rust-windows-lab.yml` is the earlier spike (hev under MSYS2 with Wintun); it is
  evidence and ships nothing.
- **K10, notices.** `notices.yml`: `THIRD_PARTY_NOTICES.md` must equal what
  `scripts/notices.py` generates from the app's `Cargo.lock` files and the core's at the pin,
  and every crate must have a licence in `scripts/notices-licences.json`. After a pin move the
  `notices` step of `pin-move.d` writes the block. A dispatch with `pin=<sha>` reads another
  commit.
- **K11, 32-bit.** The engine's or the CLI's smoke under qemu-user armv7 on ubuntu. This
  repository has no workflow for it yet; `core-build.yml` builds the armv7 musl library and
  `release.yml` uses qemu only for its own packaging.
- **K12, reference configs.** The `rust-core-configs` job of `pr-checks.yml`
  (`scripts/check-rust-core-configs.sh`): the shipped engine must parse and start every
  `core-*.json` under the reference configs, and a registered kind with no reference fails.

### When CI runs

A topic branch (`rust/t/*`) runs the light checks only: `pr-checks.yml`, `parity.yml`,
`notices.yml`, `rust-cli.yml` and `release-dry.yml`, each once, on the pull request into
`rust/all` (their push triggers name `rust/all`, `main` and `ghostlane`, so a topic push and its
pull request do not both run them).

The heavy suites run on a push to `rust/all`, that is after the lead's batch merge, and by
dispatch, each on its own `paths`: `core-build.yml`, `rust-core.yml`, `rust-core-android.yml`,
`rust-ios.yml`, `rust-desktop.yml`, `rust-jitsi.yml`, `rust-windows-sys.yml`,
`rust-windows-tun.yml`, `rust-windows-lab.yml`, `rust-cli-engine.yml`, `rust-cli-netns.yml`,
`gate-fakes.yml` and `ios-no-go.yml`. None has a `pull_request` trigger or a push
trigger for `rust/t/**`. `gate.yml` and `release.yml` keep their triggers. `no-go.yml` (the K1
checker's own tests, cheap) runs on pull requests into `main` and `rust/all` that touch the checker,
and by dispatch on a release's or a dry run's assets.

When a task needs a heavy suite on its topic branch, dispatch that one workflow once, when the
rest is green: `/root/gl-rust-handoff/p6/tools/gh.sh dispatch ghostlane-project/ghostlane
<workflow>.yml rust/t/<ID>-<name> [inputs.json]` (an inputs file such as `{"pin":"<sha>"}` only where the workflow has the input). Never
`release.yml`, `gate.yml` and the publishing workflows.

### The workflows

Every file under `.github/workflows/` is named here, with what it is for.

- `pr-checks.yml`: the cheap half of the pipeline on every pull request and on a push to
  `rust/all`, `main` and `ghostlane` (one ubuntu runner plus jobs on macOS); holds K7, K8 and K12 as above and the `rust_core` scope.
- `parity.yml`: `scripts/parity-ledger.py` against `docs/rust-parity/`, its self-test, and the
  check that this runbook names every check (K1 to K12, every workflow file and every step of
  `scripts/pin-move.d/`). A red there names the missing item: add it to this file.
- `core-build.yml`: the Rust core built once per commit for every target (Android, Apple, Linux
  musl, Windows), BoringSSL proven from the built file's symbols; `pin=<sha>` builds another
  commit; on `rust/all` and by dispatch. The Windows job is allowed to fail and says which crates stopped it.
- `rust-core.yml` (on `rust/all` and by dispatch): the core at the pin: K5 (`lines`), the fork's upstream CI jobs (`upstream`),
  the fork's guards (`fork-guards`) and the offline script tests (`scripts`).
- `rust-core-android.yml` (on `rust/all` and by dispatch): the Android library, the smoke test, the app with the core packed in,
  and K3.
- `rust-ios.yml` (on `rust/all` and by dispatch): K6.
- `rust-desktop.yml` (on `rust/all` and by dispatch): the events fixture check (`events`) and the desktop's legs on Linux,
  macOS and Windows (`desktop`), K2 on Linux.
- `rust-cli.yml`: the Rust CLI's format, build, tests and clippy. A light check: on pull requests
  into `rust/all`, and on a push to `rust/all`, `main` and `ghostlane`.
- `rust-cli-netns.yml`: K2 for the Rust CLI. On `rust/all`, `main`, `ghostlane` and by dispatch.
- `rust-cli-engine.yml`: the CLI's engine child against the real `ghostlane-engine` and the
  official Xray releases, and the K5 lines through it. On `rust/all`, `main`, `ghostlane` and by
  dispatch.
- `rust-jitsi.yml` (on `rust/all` and by dispatch): K4's Jitsi leg on the fork's fake.
- `rust-windows-sys.yml` (on `rust/all` and by dispatch): K9's system pieces.
- `rust-windows-tun.yml` (on `rust/all` and by dispatch): K9's tunnel: hev's Wintun adapter and the split routes, the engine's way out, the DNS guard and the sweep after a kill, as an administrator on windows-2025.
- `rust-windows-lab.yml`: the Windows spike, by hand, evidence only (also on a push to `rust/all`,
  `main` and `ghostlane` that touches its own files).
- `no-go.yml`: K1 (on `rust/all`, `main`, `ghostlane` and by dispatch): its self-test on ubuntu and macOS, and by hand the scan of a published
  release's assets or a run's artifacts.
- `ios-no-go.yml`: I2's check that the iPhone build names no Go (the Go frameworks' workflow and
  scripts are gone, Gradle and the Xcode project ask for no Go, `build-ios` reads the app for
  Go). It runs on a push to `rust/all` and by dispatch.
- `notices.yml`: K10. On pull requests, and on a push to `rust/all`, `main` and `ghostlane`.
- `release-dry.yml` (pull requests, and a push to `rust/all`, `main` and `ghostlane`): `scripts/test-release-dry.sh`, which reads `release.yml` and `gate.yml`,
  runs `release-guard.sh` and `gate-resolve.sh` against a stand-in for GitHub, and plants each
  violation in a copy. A dry run must be unable to sign, upload or publish.
- `release.yml`: the release build, by hand only. Never dispatched from a task branch.
- `gate.yml`: the release gate against live rooms; called by `release.yml`, by hand, daily and
  on a pin change off `rust/`. Dry on any `rust/` branch. Its `fakes` job is `gate-fakes.yml`, K4's: the engine's
  suite with `ghostlane-engine` as the client `rust` against the fake carriers, no room and no
  secret, in every run that is not skipped by request, a dry run included; a report with no
  `rust` client fails the verdict (`docs/release-gate.md`, "The Rust client").
- `gate-fakes.yml`: K4 on its own and as the `fakes` job of `gate.yml`: the core at the pin
  (or `pin=<sha>`) builds `ghostlane-engine`, which runs the engine's suite as the client `rust`
  against the SaluteJazz, VK Calls and MTS Link fakes, then the reports go through the gate's
  own fold and decision. No room, no secret; it runs on a push to `rust/all` on its own paths,
  which include the pin, by dispatch, and as the `fakes` job of `gate.yml`.
- `hev-tunnel.yml`: the tun2socks for iOS, built by hand once per pin (macOS minutes cost ten
  times Linux's).
- `cli-repo.yml`, `play-listing.yml`, `play-signing-key.yml`: by hand, for publication to the
  apt and dnf repositories, the Play listing and the Play signing key export. A task never
  dispatches them.
- `dependabot-auto-merge.yml`: merges a dependency update only after PR Checks has passed.

## How to read a red K1

`scripts/check-no-go.sh FILE...` prints, for each file, one status line, a line saying how many
containers it opened and files it read, and then one `GO` line per file that is Go with the
nesting chain (for example an APK, a `.so` inside it, and the file's name) and the signals
found. The same is written as a table to the job summary.

- `FAIL: Go`: the artefact holds Go and has no entry on the allow list. The `GO` lines say where
  and by which signal: a Go build id, the `go:buildinfo` magic or a section named for Go, a
  pclntab header, the runtime's symbols (`runtime.goexit`, `runtime.gopanic`, `go.itab`,
  `_cgo_`), or the file's name (`libgojni.so`, `libsingboxcore.so`, `libxraycore.so`,
  `sing-box`, `xray`, `olcrtc` and the like). Fix the build so that the file is not shipped.
  Nothing is added to `scripts/no-go-allow.d/` for it.
- `FAIL: STALE allow list entry`: the artefact was opened completely, is clean, and still has an
  entry. Delete the named `.allow` file in the commit that made the artefact clean. The list
  only shrinks.
- `NOT OPENED` (exit 3): something inside could not be opened, for example a DMG on an ubuntu
  job, or a format whose tool the runner lacks. The rest was checked and nothing was found, but
  this is not a pass. Run the check where the container opens (a DMG on macOS), or by hand.
- `ALLOWED`: Go is still in it, and the artefact is on the allow list; the exit is 0.
- Exit codes: 0 clean or allowed, 1 Go found or a stale entry, 2 a usage error, 3 not opened.

The self-test (`scripts/test-check-no-go.sh`, job `selftest` of `no-go.yml`) makes a stripped Go
hello with no build id and plants it at each nesting; a red self-test means the check itself no
longer finds Go and no scan result can be trusted. For a first dry run of a new artefact,
`check-no-go.sh --suggest FILE` prints the allow list file name its entry would have.
