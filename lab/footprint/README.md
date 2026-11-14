# Core footprint lab

What the client cores cost: memory, throughput and CPU, measured on GitHub's
runners against servers the job starts on the same runner, for planning the
network cores against the iPhone extension's ceiling of about 50 MB of
phys_footprint. The Rust core, xray-rust, is measured as Ghostlane's fork
builds it ([ghostlane-project/xray-rust](https://github.com/ghostlane-project/xray-rust),
at pinned commits of named branches), with upstream's v0.9.0 kept as the
historical baseline; on macOS a job of its own measures which allocator setup
keeps its footprint down (`alloc-rows`, below). The Go cores the app ships
(sing-box, Xray and the olcRTC engine, built exactly as the app builds them),
sing-box behind hev-socks5-tunnel and the iOS shape rows are still in the lab,
switched off while it measures xray-rust (see "What a run measures now").
Nothing gates on this; it is a measurement for planning.

## Running it

Push the branch `lab/core-footprint`. `.github/workflows/lab-footprint.yml` runs
on a push to that branch and on nothing else (not main, not a pull request, no
schedule), and it reads no secret. A run takes about 45 minutes when it
builds xray-rust and about 30 when the actions cache already holds the builds
(each keyed by its commit, toolchain, lab patches and build script; an entry
an older build script wrote for the same commit, toolchain and patches is
reused). The desktop jobs and the allocator job run side by side.
PR Checks also starts on the same push, because its `push:` trigger has no
branch filter.

Each job prints a compact table in its log and uploads its results:

| artifact | job |
|---|---|
| `lab-footprint-linux-x86_64` | ubuntu-latest (x86_64), with the gVisor tun run, the hev run and the iOS shape rows |
| `lab-footprint-macos-arm64` | macos-latest (Apple silicon), the stand-in for an iPhone |
| `lab-footprint-android` | ubuntu-latest with KVM, an x86_64 Android 11 (API 30) emulator (off now) |
| `lab-alloc-macos-arm64` | macos-latest: the allocator rows (`alloc-rows/alloc-rows.json`, per run its config, the core's log, the relief log and the footprint/vmmap texts), `xray-rust-alloc.build.json`, `xray-rust-alloc-build.log` when this run built it |
| `lab-footprint-all` | every `results.json` merged; the run's summary page has the same table |
| `lab-heaptrack-linux-x86_64` | the heap profile's heaptrack data files and heaptrack_print's text (below), kept 7 days |

Each artifact holds `results.json` (one object per core and scenario), `runs/`
(per run: the config, the core's log, the harness's runtime samples, and for
xray-rust its `config check --json` report; `runs/hello-*/first-flight.bin` is
the bytes a core sent first), `servers/` (the server's config and logs) and
`harness.versions.txt` (what the module resolution chose; Go rows only). The
desktop artifacts also hold a build record per xray-rust build,
`xray-rust-{main,up,hello,baseline}.build.json` and on Linux
`xray-rust-lines.build.json` (repo, ref, commit, what the ref named when it
was built, rustc, the release profile and any override, the lab patches with
their sha256, build seconds, bytes, the run that built it), and
`xray-rust-<build>-build.log` for each this run built; `hello-parity.json`
(the ClientHello parity of every client against a real Chrome, below); on
Linux `heaptrack.version.txt` and `heap-profile/` (`heap-profile.json`, and
per run its config and logs).

## What is built, and how

| what | built as | source of the recipe |
|---|---|---|
| sing-box (exec) | `scripts/build-singbox.sh` on the desktops; GOOS=android x86_64 through the NDK on Android | the desktop jobs and `build-android` in `release.yml` |
| Xray (exec) | the official 26.7.11 zip on the desktops; GOOS=android x86_64 from the v26.7.11 tag on Android | `release.yml` |
| cores harness | one Go binary linking the patched sing-box, libXray with its patched xray-core, and the olcRTC engine's `mobile` package, tags `with_gvisor,with_quic,with_utls,with_clash_api,olcrtc_lean`, `-ldflags "-s -w"` (`harness/build.sh`) | `scripts/build-cores-ios.sh`, which builds the iPhone's `Cores.xcframework` |
| olcRTC | `cmd/olcrtc` as the desktop app builds it, and the engine's `internal/e2e` package as a test binary with `-tags olcrtc_lean` | `desktopApp/build.gradle.kts`, `sharedUI/olcrtc-bin/build.gradle.kts` |
| the app | `assembleGithubDebug` with `-Polcbox.android.abiFilters=x86_64` | `androidApp/build.gradle.kts` |
| server | Xray 26.4.25, the version `scripts/xray-servers.txt` names for the platform's exits | `xray-assets.txt` (sha256 checked) |
| xray-rust (exec) | the repository at the pinned commit, fetched by its hash (the fork, [ghostlane-project/xray-rust](https://github.com/ghostlane-project/xray-rust), at `ghostlane`, `gl/xhttp-upload-memory` and `gl/chrome-156-hello`; upstream, [aimalygin/xray-rust](https://github.com/aimalygin/xray-rust), at v0.9.0); `cargo +<toolchain> build --locked --release -p xray-cli` with the toolchain its `rust-toolchain.toml` names and the workspace's `[profile.release]` (codegen-units 1, thin LTO, panic unwind, symbols stripped) (`rust-core-build.sh`), each in a directory and a cache entry of its own | the project's README "Quick start" (`cargo build --locked -p xray-cli`) with `--release`; its mobile scripts build `xray-ffi` with the same profile |
| xray-rust with line tables (Linux, the heap profile only) | `gl/xhttp-upload-memory` the same way, with `CARGO_PROFILE_RELEASE_DEBUG=line-tables-only` and `CARGO_PROFILE_RELEASE_STRIP=none` over `[profile.release]` (`rust-core-build.sh` with `XRS_LINE_TABLES=1`): the same code, with a file and a line for each frame | the heap profile's need; no row runs it |
| xray-rust with the allocator switch (macOS, the allocator rows only) | `gl/xhttp-upload-memory` the same way with `xray-rust-patches/lab-allocator-switch.patch` applied (`XRS_PATCHES`): `crates/xray-cli/src/lab_alloc.rs`, a global allocator that hands every allocation to the system allocator or to mimalloc 3 (the `mimalloc` crate 0.1.52, `libmimalloc-sys` 0.1.49, pinned in `Cargo.lock`), and a relief thread, both chosen by environment variables | the allocator rows' need; lab code, never shipped |
| hev-socks5-tunnel | a recursive clone checked out at `scripts/hev-pins.sh`'s `HEV_COMMIT` with its submodules, then `make clean exec` (`hev-build.sh`) | the iPhone's pin and the checkout of `.github/workflows/hev-tunnel.yml`, built with the make target of `desktopApp/build.gradle.kts` `buildHevSocks5TunnelLinux` (the desktop app and Android run it on their own patched copy, `androidApp/src/main/jni/hev-socks5-tunnel`, 2.14.4, which the lab does not build) |

The Go pins come from `scripts/cores-pins.sh`; each job fails first if the
workflow's copies disagree with it or with `release.yml`. hev's pin is read
from `scripts/hev-pins.sh`. xray-rust's pins live in the workflow's `env`:
`XRS_TOOLCHAIN` (1.96.0), and a ref and a commit per build: `XRS_MAIN_*`
(`ghostlane` at 4a61b057, upstream's head: the main rows), `XRS_UP_*`
(`gl/xhttp-upload-memory` at 08ed9668, ghostlane plus the XHTTP packet-up
upload buffers fix: XHTTP's rows beside the main ones, the allocator rows,
the heap profile), `XRS_HELLO_*` (`gl/chrome-156-hello` at 00b80ddb, the
fork's Chrome 156 profile: the ClientHello rows only) and `XRS_BASE_*`
(upstream's v0.9.0 at 30928ad5: the historical baseline). The build fetches
the commit by its hash and refuses a checkout whose `rust-toolchain.toml`
asks for another channel; when the branch has moved on since, it prints a
notice and the build record keeps both (`commit`, `ref_head_at_build`).
Moving a pin is a change to the workflow.

## What a run measures now

`LAB_GO_ROWS` (the workflow's `env`) is `"0"`: the Go rows (the cores
harness, sing-box and Xray as executables, the tun, hev and iOS shape rows,
the idle harness, the olcRTC soaks), their builds, their ClientHellos and
size rows, `udp-flow` and the Android emulator job (its own `if: false`) are
off; their figures stand from earlier runs (43532557527, 43538233589,
43553887055) and nothing in this round touches them. Also dropped, as known:
v0.9.0's `cli defaults` rows, its XHTTP window rows (gate 2.8, run
43553887055), its one-worker rows and its ClientHellos; the macOS heap profile
(run 43553887055 took phys_footprint apart; the allocator rows below answer
what it raised); Reality's and Hysteria2's one-worker rows. `"1"` brings the
Go rows back (`lab.py desktop` without `--no-go-rows`).

What runs:

| job | rows |
|---|---|
| desktop, both platforms | `ghostlane`: `cli defaults` on the four lines; `mobile #1`, `mobile #2` on the four lines; the relay buffer rows; WebTunnel's one-worker rows. `gl/xhttp-upload-memory`: XHTTP's `mobile #1`, `mobile #2` and one-worker rows. v0.9.0: `mobile profile` on the four lines. `gl/chrome-156-hello`: the ClientHellos, beside a real Chrome. Size rows. |
| desktop, Linux | the heap profile of `gl/xhttp-upload-memory`'s XHTTP line under heaptrack |
| alloc, macOS | the allocator rows |

## A run

The server is one Xray 26.4.25 with three inbounds on loopback: VLESS Reality
Vision, VLESS XHTTP Reality and Hysteria2 with Salamander, shaped as the exits
shape them. A second Xray 26.4.25 process holds WebTunnel's inbound (VLESS over
httpupgrade, with the TLS an exit's nginx ends ended by Xray itself), so a field
that version refuses fails only the WebTunnel rows. Its one outbound redirects every destination to a local page
(`loadgen serve`). Reality mirrors a local TLS 1.3 site (`loadgen tlsdest`).
Clients fetch `http://203.0.113.10/` (TEST-NET-3): nothing answers that address
but the server's redirect, so a byte that arrives crossed the core and the
server.

Client configs are the app's own: the committed reference configs that
`SingBoxConfigDumpTest` holds equal to `SingBoxConfig.build`, and for Xray the
XHTTP shapes `XrayConfigDumpTest` writes (copied into `shapes/`; the `shapes` job
dumps them again and fails on any difference). Only addresses, ports, keys,
names and the XHTTP path are replaced. WebTunnel has no committed reference
config: its client is `vless-reality.json` changed only where
`SingBoxConfig.kt` writes a TLS httpupgrade line differently (no flow, no
Reality block, the httpupgrade transport), plus `insecure` for the lab's
self-signed certificate (`lab.py` `singbox_webtunnel`). On Android the app
builds its own WebTunnel config from the link.

The iOS shape rows' Phase 0 configs are not on this branch's app yet: they
are copied byte for byte from the uncommitted branch
`feat/ios-singbox-behind-hev` into `shapes/` (`ios-socks-reality.json`,
`ios-socks-hysteria2.json`, `bypass-ios-socks-reality.json`), and
`shapes/ios-socks.provenance.txt` says where from, when, and pins each copy's
sha256, which `lab.py` checks before it reads one. `lab.py` also refuses a
copy that differs, apart from its inbound, from the committed tun reference
of the same line, and the `shapes` job holds each copy, apart from its
inbound, to the builder's fresh tun dump. What the runner needs is replaced in
code (`lab.py` `ios_socks_shape`, `ios_tun_shape`): the lab server's address,
port, keys and names; `cache_file.path`, into the run directory; the direct
resolver placeholder, by `DirectResolver.pick` over the runner's resolvers, as
the extension does; and Bypass Russia's `@rules/` paths, to the `.srs` files
the app bundles (`sharedUI/src/commonMain/composeResources/files/rules/`).

xray-rust reads Xray-style JSON (a documented subset: `docs/config-compatibility.md`
at the tag). Its client configs are the same lines in that form, with the
app's Xray SOCKS inbound (`shapes/vless-xhttp.json`): the XHTTP line is the
app's Xray shape unchanged, and the Reality Vision, Hysteria2 and WebTunnel
lines are translated field by field from the sing-box configs above
(`lab.py` `xrs_*`, each refusing to build when its source config moved). Where
the translation cannot be exact the row's notes say so: `insecure` becomes
`pinnedPeerCertSha256` of the lab's certificate (xray-rust refuses
`allowInsecure`), sing-box's `packet_encoding` has no field there, and there is
no ChromeParrot QUIC hello. Before each run, `xray-rust config check --json`
reads the config; a refusal fails the row by name, and its warnings go into the
notes.

Per run: start the core; wait until 1000 bytes cross it; sample for 6 s idle;
download 500 MB; pause 2 s; upload 100 MB; sample 5 s idle; then the burst: 32
downloads of 4 MB at once, pause 2 s, 32 uploads of 2 MB at once, 3 s tail,
each transfer on a connection of its own (`loadgen burst`); stop. Samples every
0.2 s on the desktops, every 0.5 s on the emulator.

A desktop job runs, in order: the Go rows and the tun rows, the idle harness,
the olcRTC soaks (with `LAB_GO_ROWS=1`), the xray-rust rows, the
ClientHellos. One time budget covers them all, so a new core that stalls can
only push its own rows and the ClientHellos into "skipped: out of time
budget", never a Go figure.

The burst is there because one transfer never shows what each connection
costs a core (goroutines, their stacks, per-connection buffers), and that is
what killed the iPhone extension: it died with 23 connections at once and 1117
to 1510 goroutines under a speed test.

Rows:

- **cores harness** (desktops): Reality, Hysteria2 and WebTunnel through
  sing-box, XHTTP through libXray, each with `ios-limits` and with
  `go-defaults`; the harness with no core started; on Linux, Reality through
  sing-box's gVisor tun (`ios-tun-reality.json`, root via sudo).
- **sing-box (exec)**, **xray (exec)**: the binaries the desktop app and the
  Android app run as child processes, Go defaults.
- **olcrtc engine (loopback soak)**: the engine's `TestLocalThroughputSoak`,
  vp8channel and datachannel, 30 s each. `ios-limits` is `GOGC=10
  GOMEMLIMIT=40MiB` plus the phones' constrained buffer profile, the one
  `mobile.New()` turns on (`engine-build.sh` adds a test file to the checkout
  that turns it on when `OLCRTC_LAB_CONSTRAINED=1`; `extra.constrained_buffers`
  says whether the log confirmed it). `go-defaults` keeps the server-sized
  buffers, which is how the desktop app's `olcrtc` binary runs.
- **Android**: the x86_64 cores from the APK build under `adb shell`; then the
  debug app itself: a line imported through `ghostlane://add?url=`, the VPN
  started with the debug-only `DEBUG_START_VPN` broadcast, traffic from the
  shell through the app's tun.
- **hev + cores harness: sing-box** (Linux, root via sudo): the same Reality
  line with hev-socks5-tunnel owning the tun and the harness (`ios-limits`) on
  SOCKS behind it, the shape the iPhone already uses for XHTTP and olcRTC.
  hev runs with `HevTunnel.swift`'s configuration (MTU 9000, task stack 24576,
  TCP buffer 4096, 768 sessions; `lab.py` `hev_config` refuses to run when the
  Swift writes something else) plus a tun name and address. Its memory columns
  are hev and the harness summed; `extra.hev_*` and `extra.harness_*` hold each
  process alone; the Go runtime columns are the harness's.
- **ios-shape sing-box gVisor tun** and **ios-shape sing-box+hev** (Linux,
  root via sudo): three lines each, Reality Vision, Hysteria2 and Reality
  under Bypass Russia, in the cores harness (`ios-limits`). The gVisor rows
  are the iPhone today: `ios-tun-reality.json` and `bypass-tun-reality.json`,
  and for Hysteria2 in Global, which has no committed tun reference,
  `ios-tun-reality.json` with the Hysteria2 outbound of
  `bypass-tun-hysteria2.json`; auto_route off and a named tun. The hev rows
  are Phase 0: the copied SOCKS configs, sing-box on 127.0.0.1:10810 with its
  login, and hev at the iPhone's pin in front with `HevTunnel.configuration`
  for the port and login that `TunArrangement.singBoxSocks` reads from the
  config (`lab.py` `ios_hev_socks`), plus a tun name and the tun's address
  172.19.0.1 (`LibboxPlatform.Tun.address`). In both, the kernel routes the
  fake-IP range 198.18.0.0/15 and the tun's resolver 172.19.0.2 into the tun.
  Before any load, three checks: (1) an A query for `page.lab.test` to
  172.19.0.2 through the tun gets an address in 198.18.0.0/15; (2) HTTP to
  that name at that address brings the page's own bytes (65536 of them,
  compared with loadgen's pattern), which only the Reality or Hysteria2 server
  can have redirected there; (3) eight more UDP exchanges, each a query for a
  new name on a socket of its own, each answered with a fake address of its
  own (the lab has no UDP echo; behind hev these cross hev's SOCKS5 UDP
  relay). A failed check fails the row before the load, with the logs. Then
  the run as above, with every transfer to the fake address of
  `page.lab.test`, so each connection is a fake-IP lookup in sing-box. The
  summary has a table of the checks.
- **xray-rust <ref> (exec)** (desktops): the four lines through the CLI.
  `cli defaults` is the binary as it starts (2 Tokio workers, runtime profile
  Default, which on a desktop target is the Standard transport memory class).
  `mobile #1` and `mobile #2` (`mobile profile` for v0.9.0) are what the
  library runs with on a phone: `XRAY_TUN_PROFILE=mobile` (the Constrained
  memory class) and the Tokio workers `xray-ffi` would size on this runner
  (available parallelism clamped to 2..6). The `version` column names the
  repository, the ref, the commit, rustc and the release profile. The project
  documents no separate library or iOS cargo profile, so there is no second
  build. The fork's rows run in two passes, `#1` then `#2`, each pass holding
  every mobile row once, so each figure has two runs a few minutes apart.
  Every xray-rust row records its threads (`extra.threads_idle`,
  `extra.threads_after_burst`: by name on Linux, the count on the Mac).
- **relay buffer rows** (`mob buf16K`, `mob buf32K`, `mob buf64K`; Reality
  Vision and WebTunnel, both desktops): the mobile profile with
  `policy.levels."0".bufferSize` at 16, 32 and 64 (KiB), the field the core
  reads for a relay's read size (`crates/xray-core-rs/src/policy.rs`:
  `relay_buffer_size`, KiB, clamped to 1 KiB..1 MiB; a VLESS outbound takes
  its user's level, 0 here), read against the same pass's `mobile` row (the
  default, 128 KiB). In the fork a relay's first read is 4 KiB and a read past
  it lands in a 128 KiB buffer lent by a process-wide pool whatever the cap
  (`policy/relay_buffer.rs`, `LENT_BUFFER_SIZE`; the Constrained class keeps
  16 of them free, each worker thread one spare), so the cap bounds what one
  read takes, not the buffer it reads into.
- **one-worker rows** (`mob 1 worker`; XHTTP on `gl/xhttp-upload-memory`,
  WebTunnel on `ghostlane`): the mobile profile with `TOKIO_WORKER_THREADS=1`,
  Tokio's multi-thread runtime with one worker (`xray-cli` leaves the
  variable to Tokio and sets 2 only when it is unset), read beside the same
  build's `mobile` row of the same pass for peak memory, CPU per GB and
  throughput. Reality's and Hysteria2's ran in run 43553887055 (v0.9.0).
  `xray-ffi` sets its own worker count and reads no variable, so one worker on
  a phone would need a change there.
- **client hello: <line>** (desktops): the first bytes a core sends on a TCP
  line, read by a relay in front of the server port: xray-rust on Reality,
  XHTTP and WebTunnel; sing-box (exec) on Reality and WebTunnel; xray (exec) on
  XHTTP. Each client is captured three times, each time from a fresh process,
  so what it draws per connection shows against what it keeps; a second fetch
  per process opens a later connection, kept apart (`extra.later_connections`:
  JA4, whether it resumed with `pre_shared_key`, its extension order). The
  ClientHello table gives JA4, the key shares, ALPN, the hello's size, whether
  an ECH GREASE extension went out and the extension count; `extra` has JA3,
  the full lists, each extension's length and content where it matters
  (`ext_details`: ECH's suite and lengths, compress_certificate's algorithms,
  ALPS's codepoint and protocols, psk modes, point formats), the record
  layer's version, and `captures`/`variation` (each field per capture, and
  which ones moved). Hysteria2's hello travels in a QUIC Initial under
  Salamander and is not read.
- **client hello: chrome, ...** (desktops, gate 2.2, tasks.md 6.2): a real
  Google Chrome, the runner's own (its `--version` is the row's version), run
  headless with a fresh profile per capture, navigating to
  `https://<name>:20550/` with `--host-resolver-rules=MAP <name> 127.0.0.1`,
  so its first connection goes through the same relay: once to the Reality
  server name `reality.lab.test` (the relay in front of the Reality port,
  which hands an unauthenticated hello to its fallback site) and once to a
  plain TLS server, `webtunnel.lab.test` (Xray's TLS on the WebTunnel port).
  No DNS answers the name, so there is no ECH config and Chrome sends its ECH
  GREASE, as it does for any site without an HTTPS record. The flags change no
  TLS setting; a fresh profile with background networking off has no
  variations seed, so a server-side field trial a user's Chrome may be in is
  not in this hello. Three captures each.
- **ClientHello parity** (the summary's parity tables, `hello-parity.json`):
  per platform and target (Reality server names, a plain TLS server), each
  field for Chrome and every core: JA4, record and legacy versions, session id
  length, the cipher list with its GREASE position, supported_versions, the
  extension set (GREASE aside) and whether its order moves between captures
  (`permutes`: Chrome has shuffled its extensions since 110), each
  extension's length (the server name's own bytes taken out, since the lines
  use names of different lengths), where GREASE sits, supported_groups,
  key_share, ALPN, signature algorithms, the ECH GREASE extension and its
  length, padding, compress_certificate, ALPS's codepoint, psk modes, point
  formats, status_request, renegotiation_info, and the hello's and the first
  record's lengths. A field a core holds fixed unlike Chrome's is marked
  **≠**; a field drawn per connection (ECH's payload length and the lengths
  that follow it) is marked ≈ when the captures came out differently, which
  three draws alone do not make a difference of shape.
- Every xray-rust row also records the peak of each half of the burst apart
  (`burst_down_half_peak_*`, `burst_up_half_peak_*`: 32 downloads at once,
  then 32 uploads at once), sampled every 0.2 s, beside the lifetime peak.
  (The XHTTP window rows of gate 2.8 ran on v0.9.0 in run 43553887055 and are
  out of the lab.)
- **binary size** rows for every binary measured (xray-rust and hev
  included) and every `.so` in the APK.

## Reading the figures

| field | meaning |
|---|---|
| `memory_metric` | what the three memory columns hold: resident size on the desktops and for the Android cores; PSS of the app process plus the core process for the app rows |
| `idle_rss_mb` | median over the second half of the 6 s idle settle, the core up and nothing moving |
| `steady_rss_mb` | median over the middle half of the 500 MB download |
| `peak_rss_mb` | the highest in the process's life, start-up included: VmHWM on Linux and for the Android cores, the largest sample elsewhere (the macOS lifetime peak footprint is in `extra`) |
| `go_sys_minus_released_mb` | harness rows only: the Go runtime's `memory/classes/total` minus `heap/released` at its peak; the extension's trace calls it `sys - rel`. `extra` has its idle and steady values and the heap |
| `mbps_down`, `mbps_up` | 10^6 bits per second over the whole transfer, first byte included |
| `cpu_s_per_gb` | CPU seconds (user + system) of the measured process(es) during both transfers, per 10^9 bytes moved |
| `extra` | macOS `phys_footprint` (idle, steady, lifetime peak), PSS, per-direction CPU, Go GC cycles, the limit and GC percent in force, MemoryWatch releases; for xray-rust the build record and the core version `config check` reported; for the hev row each process alone |
| `fp-pk` / `macOS phys_footprint` | macOS only: the table's `fp-pk` is the lifetime peak `phys_footprint`, and the summary's column gives idle / steady / peak / burst; the figure jetsam holds an iPhone extension to, so the headline on macOS |
| `extra.burst_*` | the burst: peak memory over it (`burst_peak_mb`, same metric as the row; macOS `burst_peak_phys_footprint_mb`), aggregate Mbit/s each way, transfers that finished whole, CPU s per GB; harness rows also the Go runtime's peak goroutines, `sys - rel` and heap over it. The table's `burst` and `b-gor` columns are `burst_peak_mb` and `burst_go_goroutines_peak` |
| `extra.ios_checks` | the iOS shape rows: each check's `ok`, its `detail` and its figures (also `runs/<row>/ios-checks.json`); `extra.arrangement` is what `TunArrangement` would decide for the config |
| `notes` | how the row deviates from the app; `FAILED:` or `skipped:` when a run did not happen, with the reason |

Memory is MiB (2^20). The macOS CPU times are checked against `ps` each run,
since `proc_pid_rusage` has reported them in mach ticks on Apple silicon.

### `ios-limits` against `go-defaults`

`-mode ios` applies what `iosApp/PacketTunnel/PacketTunnelProvider.swift`
`startEngine` applies, in its order: `LibboxSetMemoryLimit(true)` (GC percent
10; its 45 MiB is set by hand, because libbox sets it only when built for iOS);
for Xray the h2 receive windows of `XrayEngine.swift` and libXray's
`InitForceFree` (GC percent 10, 30 MiB, `debug.FreeOSMemory` every second),
which only an iOS build of libXray contains; then `MobileSetMemoryLimit(40 MiB)`;
and `MemoryWatch.swift`'s release (`FreeOSMemory` above a 36 MiB footprint, at
most every 5 s, checked four times a second). `-mode plain` applies none of it.

## Where a new UDP flow's time goes (`udp-flow`)

The ios-shape hev rows answered one DNS query to the tun's resolver in about
0.42 s where the gVisor rows took 0.012 s, and each new UDP socket cost about
that again. `lab.py udp-flow` (Linux, root via sudo; the step after Measure,
so no row moves) takes that shape apart, one change per variant. Each variant
sends 20 A queries to 172.19.0.2, each for a new name on a UDP socket of its
own (behind hev, a new SOCKS5 association each), and opens 20 fresh TCP
connections, each with a GET of 1024 bytes of the page; it reports the median
and the p90 (nearest rank) of the query time, of the connect and of the first
byte, both counted from the start of the connect.

| id | what changes |
|---|---|
| V1 | nothing: `ios-socks-reality.json` behind hev at the pin, as the ios-shape hev row runs it |
| V2 | V1 without the route rule `{"action":"sniff"}` |
| V3 | V1 without the SOCKS login, in sing-box and in hev |
| V4 | no hev and no tun: a python SOCKS5 client talks to sing-box's socks inbound with the login, a fresh association (or CONNECT) each time, timing the method reply, the login reply, the request reply and the datagram's answer (or the first byte); three ways: no option, `TCP_NODELAY`, and `MSG_MORE` on the method offer and the login, as hev at the pin sends them |
| V5 | hev at the pin with the login in front of `lab.py flow-socks`, a python SOCKS5 server that answers DNS itself and sends every CONNECT to the page: hev without sing-box |
| V6 | V5 with hev's `udp: 'tcp'` (UDP in TCP, hev's FWD_UDP), which the python server speaks and sing-box does not |
| V7 | the control: `ios-tun-reality.json` on sing-box's own gVisor tun, as the ios-shape gVisor row runs it |
| V8 | V1 with hev's `pipeline: true` |
| V9 | V1 with `hev-socks5-tunnel-nomore`: the pin with `MSG_MORE` dropped from the two handshake writes that pass it (`hev-build.sh` builds it second). That is the iPhone's build, since Darwin has no `MSG_MORE` and hev-socks5-core falls back to 0, and upstream's b43fa85 outside pipeline mode |

The table is in the step's log and the job summary; `udp-flow/udp-flow.json`
in each desktop artifact has every figure, `udp-flow/runs/<id>/` the configs
and logs. It starts its own servers and its own tuns (`pklab4`, `pklab5` on
Linux; `utun77`, `utun78` on the Mac).

Run 43538233589 (Linux) found the cause: hev-socks5-core f5c97b0, in the pin,
sends the method offer and the login with `MSG_MORE`, and Linux holds each of
those writes for about 200 ms. The same step runs on the Mac, where Apple's
clang builds the pin the way it builds the iPhone's copy: there `msg_more.txt`
records that `<sys/socket.h>` (the Mac's and the iPhone SDK's) has no
`MSG_MORE`, `hev-nomore.identical.txt` that the build without it is byte for
byte the pin's, and V1 is the measurement of Darwin's TCP behind hev. V4 has
no `msg_more` mode there.

Since then the Linux hev rows of Measure (the hev row and the ios-shape hev
rows) run `hev-socks5-tunnel-nomore` when it was built, so they stand for the
iPhone's code path; each row's notes name the binary.

## Where xray-rust's memory goes (`heap-profile`)

`lab.py heap-profile` (the step after the rows, with servers of its own, so
no row moves) runs xray-rust in the mobile profile on the lines `--lines`
names (Reality Vision, WebTunnel, Hysteria2, XHTTP packet-up; the workflow
now runs it on Linux only, on XHTTP, with `gl/xhttp-upload-memory`'s build
with line tables) under two loads:
the rows' burst (32 downloads of 4 MB at once, then 32 uploads of 2 MB), and
a hold: 32 connections through the core at once, a GET of 1024 bytes on each,
20 s idle, then a second GET on each to show they lived (threads of `lab.py`
itself, so the samples see only the core).

On Linux each line and load runs twice, both times the build with line
tables. Once plain: RSS (`VmHWM`), and `RssAnon`, `RssFile` and
`/proc/<pid>/smaps` at its largest sample (polled every 10 ms), smaps split by
kind (the binary, glibc's main arena, thread arenas by their 64 MiB
alignment, thread and signal stacks by the guard page below them, malloc's
mmapped chunks and the rest, shared libraries). Once under heaptrack (Ubuntu's
package): the heap's peak, the bytes each backtrace held at that moment, and,
because the core is stopped with SIGHUP (which it does not catch, so nothing
unwinds; the fork's CLI shuts down gracefully on SIGTERM and SIGINT, which
would close the hold's connections first) once the load is over and while
the hold's connections are still open, the heap live at that moment as
heaptrack's "leaked". heaptrack_print's
own merge groups by the address of the malloc call, which in Rust is the
allocator shim, one place for almost everything, so the lab reads the
unmerged backtraces (the 2000 largest) and groups them by their first frame
in xray-rust's `crates/` or `vendor/` (`site`, file:line and function), with
the call it made (`via`), the leaf-most frame outside the standard library
(`alloc_at`, often a crate's buffer: `bytes`, `h2`, the shaped rustls) and
its own callers. heaptrack slows the core, so its peak need not fall at the
plain run's moment; `RSS - heap` and `anon - heap` (plain RSS less heaptrack's
heap peak) are the non-heap part up to that difference.

On the Mac (`--xray-rust`; not in the workflow now), `footprint` and
`vmmap --summary` (with `sudo -n` when they need
root) split phys_footprint: malloc by zone (tiny, small, large, and the rest),
stacks, dirty `__DATA*` segments, and the rest by name; vmmap's MALLOC ZONE
table adds the bytes malloc handed out against the dirty pages it holds
(fragmentation). Either tool takes longer than a burst half, so the first
burst only finds the peak (phys_footprint polled every 10 ms); in each next
one the core is stopped (SIGSTOP) the moment its footprint crosses 95 % of
that peak (85 % on the third try), both tools read the frozen process, and it
goes on. The hold is read in the middle of its idle.

The tables are in the step's log and the job summary;
`heap-profile/heap-profile.json` has every figure and the top 50 sites per
run (`sites_at_peak`, and for the hold `sites_live_at_end`). heaptrack's data
files are in `lab-heaptrack-linux-x86_64`; `heaptrack_gui` or
`heaptrack_print` opens them beside a build with line tables from the same
pins.

## Which allocator setup keeps the footprint down (`alloc-rows`)

Run 43553887055's macOS heap profile found that most of malloc's dirty pages
under a burst are free but retained (XHTTP: 32.5 of 49.6 MiB), that the
footprint never comes down after a burst, and that a second burst starts
from the first one's peak and goes higher. `lab.py alloc-rows` (macOS, the
`alloc` job) measures what giving that memory back costs and saves. It runs
one build, `gl/xhttp-upload-memory` with `lab-allocator-switch.patch`, in the
mobile profile, under five setups that environment variables choose
(`lab_alloc.rs`; the core prints what is in force and the lab refuses a run
whose log does not say it):

| setup | what |
|---|---|
| a | the system allocator as the CLI has it |
| b | the system allocator; a thread calls `malloc_zone_pressure_relief(NULL, 0)` every 1 s while the process runs (`XRS_LAB_RELIEF=interval`) |
| c | the system allocator; the same call once when traffic stops (`XRS_LAB_RELIEF=idle`): a thread reads the process's CPU time every 250 ms and calls it after a 250 ms window under 2.5 ms of CPU that follows a busy one, so 250 to 500 ms after the last byte; the next busy window arms it again. The CLI has no signal of its own for "the burst is over" |
| d | mimalloc 3 as the global allocator (`XRS_LAB_ALLOC=mimalloc`), `MIMALLOC_PURGE_DELAY=0`: freed arena memory is purged (decommitted with `MADV_FREE_REUSABLE` on macOS) at once (mimalloc's `purge_delay`, default 1000 ms; the core prints mimalloc's options, which the lab checks) |
| e | mimalloc 3 with its default purge delay (1000 ms; purges run from inside later allocator calls) |

A run: start the core, wait for a byte through it, 3 s idle; the burst (32
downloads of 4 MB at once, 2 s, 32 uploads of 2 MB at once), 5 s; the same
burst again, 5 s; `footprint` and `vmmap` of the process; then the single
transfers (500 MB down, 2 s, 100 MB up) for throughput and CPU per GB, 5 s.
phys_footprint is polled every 20 ms. Per burst: its start, its sampled peak
(and each half's), the footprint 0.5, 1, 2 and 5 s after its last upload,
aggregate Mbit/s each way and CPU s per GB; over both, the kernel's lifetime
maximum. For b and c the relief log has every call: when, why, the bytes it
released, its wall and CPU time, and malloc's own statistics just before it.
Each line (Reality Vision, Hysteria2, WebTunnel, XHTTP) runs every setup
twice, in two passes with the setups interleaved. The tables are in the
step's log and the job summary; `alloc-rows/alloc-rows.json` has every
figure.

## What it cannot tell

- macOS on Apple silicon is not iOS: no jetsam, no packet tunnel extension, a
  different allocator and libraries; `phys_footprint` here is a process's on a
  desktop. The iOS-only code paths are reproduced by hand, not compiled.
- The harness drives sing-box through its box API, as `sing-box run` does, not
  through libbox's command server; a tun (gVisor's, or hev's) runs only in the
  two Linux tun rows.
- hev is a process of its own here, sampled beside the harness and summed; on
  the iPhone it is a thread of the extension, so the extension pays one
  process's overhead where the lab counts two.
- xray-rust runs as its CLI binary, not as the `xray-ffi` static library an app
  would link, and without a tun of its own; its size row is the CLI's. Its
  TLS lines pin the lab's certificate rather than skip verification, and its
  Hysteria2 has no ChromeParrot QUIC hello. xray-rust follows newer Xray
  semantics than the 26.4.25 servers; a line that cannot work against them
  fails by name with its log in `runs/`.
- Loopback is not a network. Throughput is bounded by CPU, and the server, the
  page, the client and the core share the runner's cores.
- The olcRTC soak needs no meeting service, so there is no SFU, no TURN and no
  loss; the server and the client run in one process (memory and CPU cover
  two peers), with the e2e test's vp8channel options (30 fps, batch 64), not
  the app's.
- The emulator is x86_64 behind QEMU's user-mode network, not an ARM phone on
  a radio; the APK is a debug build (not minified).
- Client inbounds carry no SOCKS login, except the ios-shape hev rows'; the
  app's Android configs carry one.
- The iOS shape rows route only the fake-IP range and the tun's resolver into
  the tun, not a default route, and nothing pins sing-box's own sockets
  (no iOS config sets `auto_detect_interface`, so that is the phone's case
  too). sing-box starts through `box.New`, not libbox's command server, and
  hev opens its own tun where the extension hands it a descriptor. Their
  Phase 0 configs are copies from an uncommitted branch, checked against this
  branch's references, not against Phase 0's builder.
- One run per cell for the Go rows; the fork's mobile, relay and one-worker
  rows and the allocator rows run twice in one job, on one runner. Run the
  workflow again for a sample from another runner.
- The allocator rows run the CLI with mimalloc or a relief thread; the
  extension links `xray-ffi`, whose allocator is the app's: a setup that wins
  here still has to be put into the library (a `#[global_allocator]` in a
  static library is the whole app's) or the extension.
- No olcRTC line through the cores harness or the Android app: those need a
  meeting service. The soak is the only olcRTC row, and it holds two peers.
- hev runs only in front of sing-box on Linux (Reality in the hev row; Reality,
  Hysteria2 and Bypass Russia in the iOS shape rows), not in front of libXray
  (the iPhone's XHTTP) and not on macOS (no root tun there). No
  sing-box front behind Xray (Android's XHTTP under Rules; the app's default
  routing is Global, `RoutingSettings.kt`), no desktop JVM, no macOS tunnel
  daemon, no CLI.

## Files

- `lab.py`: the driver (Python 3.9+, standard library): `desktop`, `android`,
  `table`, `shapes-check`, `udp-flow` (and `flow-socks`, its SOCKS5 server),
  `heap-profile`, `alloc-rows`.
- `harness/`: the cores harness (`build.sh`; `cores_stub.go` and the
  `labnocores` tag exist only to vet the harness's own code without the cores).
- `loadgen/`: the page, the TLS site, the transfers, keys and certificates
  (standard library only; the same static linux/amd64 build runs on the
  runner and under `adb shell`).
- `shapes/`: the XHTTP client shapes, and Phase 0's iOS SOCKS shapes with
  `ios-socks.provenance.txt`; `xray-assets.txt`: the Xray archives
  and their sha256; `engine-src.sh`, `engine-build.sh`: the engine at the pin.
- `rust-core-build.sh`: xray-rust at a pinned commit of a named ref
  (`XRS_REPO`, `XRS_REF`, `XRS_COMMIT`, `XRS_TOOLCHAIN`), and with
  `XRS_PATCHES` the same commit with patches from `xray-rust-patches/` applied
  (`lab-allocator-switch.patch`: the allocator rows' switch, against
  `gl/xhttp-upload-memory`), and with `XRS_LINE_TABLES=1` the build with line
  tables the heap profile runs; `hev-build.sh`:
  hev-socks5-tunnel at `scripts/hev-pins.sh`'s commit, then udp-flow's
  `hev-socks5-tunnel-nomore`.
