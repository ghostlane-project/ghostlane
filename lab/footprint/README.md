# Core footprint lab

What the Go cores the app ships cost: memory, throughput and CPU, measured on
GitHub's runners. sing-box, Xray and the olcRTC engine are built exactly as the
app builds them, and each runs against servers the job starts on the same
runner. Beside them, in the same table, a Rust core (xray-rust) on the same
lines, and on Linux sing-box behind hev-socks5-tunnel, for planning a possible
rewrite of the network cores against the iPhone extension's ceiling of about
50 MB. Also on Linux, the iOS shape rows: the iPhone's own sing-box configs,
today's (sing-box on its gVisor tun) and Phase 0's (sing-box on SOCKS behind
hev), each checked for DNS, a name and UDP through the tun before it is
loaded. Nothing gates on this; it is a measurement for planning.

## Running it

Push the branch `lab/core-footprint`. `.github/workflows/lab-footprint.yml` runs
on a push to that branch and on nothing else (not main, not a pull request, no
schedule), and it reads no secret. A run takes about 40 minutes; the desktop
jobs take about 35 when they build xray-rust and about 20 when the actions
cache already holds it (keyed by its tag, commit, toolchain and build script).
PR Checks also starts on the same push, because its `push:` trigger has no
branch filter.

Each job prints a compact table in its log and uploads its results:

| artifact | job |
|---|---|
| `lab-footprint-linux-x86_64` | ubuntu-latest (x86_64), with the gVisor tun run, the hev run and the iOS shape rows |
| `lab-footprint-macos-arm64` | macos-latest (Apple silicon), the stand-in for an iPhone |
| `lab-footprint-android` | ubuntu-latest with KVM, an x86_64 Android 11 (API 30) emulator |
| `lab-footprint-all` | every `results.json` merged; the run's summary page has the same table |

Each artifact holds `results.json` (one object per core and scenario), `runs/`
(per run: the config, the core's log, the harness's runtime samples, and for
xray-rust its `config check --json` report; `runs/hello-*/first-flight.bin` is
the bytes a core sent first), `servers/` (the server's config and logs) and
`harness.versions.txt` (what the module resolution chose). The desktop
artifacts also hold `xray-rust.build.json` (repo, tag, commit, rustc, the
release profile, build seconds, bytes, the run that built it),
`xray-rust-build.log` when this run built it, `xray-rust-latest.txt` (the
newest release against the pin), and on Linux `hev.versions.txt` and
`hev-build.log`.

## What is built, and how

| what | built as | source of the recipe |
|---|---|---|
| sing-box (exec) | `scripts/build-singbox.sh` on the desktops; GOOS=android x86_64 through the NDK on Android | the desktop jobs and `build-android` in `release.yml` |
| Xray (exec) | the official 26.7.11 zip on the desktops; GOOS=android x86_64 from the v26.7.11 tag on Android | `release.yml` |
| cores harness | one Go binary linking the patched sing-box, libXray with its patched xray-core, and the olcRTC engine's `mobile` package, tags `with_gvisor,with_quic,with_utls,with_clash_api,olcrtc_lean`, `-ldflags "-s -w"` (`harness/build.sh`) | `scripts/build-cores-ios.sh`, which builds the iPhone's `Cores.xcframework` |
| olcRTC | `cmd/olcrtc` as the desktop app builds it, and the engine's `internal/e2e` package as a test binary with `-tags olcrtc_lean` | `desktopApp/build.gradle.kts`, `sharedUI/olcrtc-bin/build.gradle.kts` |
| the app | `assembleGithubDebug` with `-Polcbox.android.abiFilters=x86_64` | `androidApp/build.gradle.kts` |
| server | Xray 26.4.25, the version `scripts/xray-servers.txt` names for the platform's exits | `xray-assets.txt` (sha256 checked) |
| xray-rust (exec) | [aimalygin/xray-rust](https://github.com/aimalygin/xray-rust) cloned at the release tag, checked against the pinned commit; `cargo +<toolchain> build --locked --release -p xray-cli` with the toolchain its `rust-toolchain.toml` names and the workspace's `[profile.release]` (codegen-units 1, thin LTO, panic unwind, symbols stripped) (`rust-core-build.sh`) | the project's README "Quick start" (`cargo build --locked -p xray-cli`) with `--release`; its mobile scripts build `xray-ffi` with the same profile |
| hev-socks5-tunnel | a recursive clone checked out at `scripts/hev-pins.sh`'s `HEV_COMMIT` with its submodules, then `make clean exec` (`hev-build.sh`) | the iPhone's pin and the checkout of `.github/workflows/hev-tunnel.yml`, built with the make target of `desktopApp/build.gradle.kts` `buildHevSocks5TunnelLinux` (the desktop app and Android run it on their own patched copy, `androidApp/src/main/jni/hev-socks5-tunnel`, 2.14.4, which the lab does not build) |

The Go pins come from `scripts/cores-pins.sh`; each job fails first if the
workflow's copies disagree with it or with `release.yml`. hev's pin is read
from `scripts/hev-pins.sh`. xray-rust's three pins (`XRAY_RUST_TAG`,
`XRAY_RUST_COMMIT`, `XRAY_RUST_TOOLCHAIN`) live in the desktop job's `env`:
v0.9.0, the newest release when they were set (read through the GitHub API),
at 30928ad5, toolchain 1.96.0. The build refuses a checkout that disagrees
with any of them, and a step of the job prints a notice when a newer release
is out; moving the pin is a change to the workflow.

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
the olcRTC soaks, the xray-rust rows, the ClientHellos. One time budget covers
them all, so a new core that stalls can only push its own rows and the
ClientHellos into "skipped: out of time budget", never a Go figure.

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
- **xray-rust vX.Y.Z (exec)** (desktops): the four lines through the CLI,
  under two settings. `cli defaults` is the binary as it starts (2 Tokio
  workers, runtime profile Default, which on a desktop target is the
  Standard transport memory class). `mobile profile` is what the library runs
  with on a phone: `XRAY_TUN_PROFILE=mobile` (the Constrained memory class) and
  the Tokio workers `xray-ffi` would size on this runner (available
  parallelism clamped to 2..6). The `version` column names the tag, the commit,
  rustc and the release profile. The project documents no separate library
  or iOS cargo profile, so there is no second build.
- **client hello: <line>** (desktops): the first bytes a core sends on a TCP
  line, read by a relay in front of the server port: xray-rust on Reality,
  XHTTP and WebTunnel; sing-box (exec) on Reality and WebTunnel; xray (exec) on
  XHTTP. The ClientHello table gives JA4, the key shares, ALPN, the hello's
  size, whether an ECH GREASE extension went out and the extension count;
  `extra` has JA3, the full lists and whether a fetch through the relay
  completed. Hysteria2's hello travels in a QUIC Initial under Salamander and
  is not read.
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
- One run per cell. Run the workflow again for a second sample.
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
  `table`, `shapes-check`.
- `harness/`: the cores harness (`build.sh`; `cores_stub.go` and the
  `labnocores` tag exist only to vet the harness's own code without the cores).
- `loadgen/`: the page, the TLS site, the transfers, keys and certificates
  (standard library only; the same static linux/amd64 build runs on the
  runner and under `adb shell`).
- `shapes/`: the XHTTP client shapes, and Phase 0's iOS SOCKS shapes with
  `ios-socks.provenance.txt`; `xray-assets.txt`: the Xray archives
  and their sha256; `engine-src.sh`, `engine-build.sh`: the engine at the pin.
- `rust-core-build.sh`: xray-rust at the workflow's pins; `hev-build.sh`:
  hev-socks5-tunnel at `scripts/hev-pins.sh`'s commit.
