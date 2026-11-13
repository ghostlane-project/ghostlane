# Core footprint lab

What the Go cores the app ships cost: memory, throughput and CPU, measured on
GitHub's runners. sing-box, Xray and the olcRTC engine are built exactly as the
app builds them, and each runs against servers the job starts on the same
runner. Nothing gates on this; it is a measurement for planning.

## Running it

Push the branch `lab/core-footprint`. `.github/workflows/lab-footprint.yml` runs
on a push to that branch and on nothing else (not main, not a pull request, no
schedule), and it reads no secret. A run takes about 40 minutes. PR Checks
also starts on the same push, because its `push:` trigger has no branch filter.

Each job prints a compact table in its log and uploads its results:

| artifact | job |
|---|---|
| `lab-footprint-linux-x86_64` | ubuntu-latest (x86_64), with the gVisor tun run |
| `lab-footprint-macos-arm64` | macos-latest (Apple silicon), the stand-in for an iPhone |
| `lab-footprint-android` | ubuntu-latest with KVM, an x86_64 Android 11 (API 30) emulator |
| `lab-footprint-all` | every `results.json` merged; the run's summary page has the same table |

Each artifact holds `results.json` (one object per core and scenario), `runs/`
(per run: the config, the core's log, the harness's runtime samples),
`servers/` (the server's config and logs) and `harness.versions.txt` (what the
module resolution chose).

## What is built, and how

| what | built as | source of the recipe |
|---|---|---|
| sing-box (exec) | `scripts/build-singbox.sh` on the desktops; GOOS=android x86_64 through the NDK on Android | the desktop jobs and `build-android` in `release.yml` |
| Xray (exec) | the official 26.7.11 zip on the desktops; GOOS=android x86_64 from the v26.7.11 tag on Android | `release.yml` |
| cores harness | one Go binary linking the patched sing-box, libXray with its patched xray-core, and the olcRTC engine's `mobile` package, tags `with_gvisor,with_quic,with_utls,with_clash_api,olcrtc_lean`, `-ldflags "-s -w"` (`harness/build.sh`) | `scripts/build-cores-ios.sh`, which builds the iPhone's `Cores.xcframework` |
| olcRTC | `cmd/olcrtc` as the desktop app builds it, and the engine's `internal/e2e` package as a test binary with `-tags olcrtc_lean` | `desktopApp/build.gradle.kts`, `sharedUI/olcrtc-bin/build.gradle.kts` |
| the app | `assembleGithubDebug` with `-Polcbox.android.abiFilters=x86_64` | `androidApp/build.gradle.kts` |
| server | Xray 26.4.25, the version `scripts/xray-servers.txt` names for the platform's exits | `xray-assets.txt` (sha256 checked) |

All pins come from `scripts/cores-pins.sh`; each job fails first if the
workflow's copies disagree with it or with `release.yml`.

## A run

The server is one Xray 26.4.25 with three inbounds on loopback: VLESS Reality
Vision, VLESS XHTTP Reality and Hysteria2 with Salamander, shaped as the exits
shape them. Its one outbound redirects every destination to a local page
(`loadgen serve`). Reality mirrors a local TLS 1.3 site (`loadgen tlsdest`).
Clients fetch `http://203.0.113.10/` (TEST-NET-3): nothing answers that address
but the server's redirect, so a byte that arrives crossed the core and the
server.

Client configs are the app's own: the committed reference configs that
`SingBoxConfigDumpTest` holds equal to `SingBoxConfig.build`, and for Xray the
XHTTP shapes `XrayConfigDumpTest` writes (copied into `shapes/`; the `shapes` job
dumps them again and fails on any difference). Only addresses, ports, keys,
names and the XHTTP path are replaced.

Per run: start the core; wait until 1000 bytes cross it; sample for 6 s idle;
download 500 MB; pause 2 s; upload 100 MB; sample 5 s idle; stop. Samples every
0.2 s on the desktops, every 0.5 s on the emulator.

Rows:

- **cores harness** (desktops): Reality and Hysteria2 through sing-box, XHTTP
  through libXray, each with `ios-limits` and with `go-defaults`; the harness
  with no core started; on Linux, Reality through sing-box's gVisor tun
  (`ios-tun-reality.json`, root via sudo).
- **sing-box (exec)**, **xray (exec)**: the binaries the desktop app and the
  Android app run as child processes, Go defaults.
- **olcrtc engine (loopback soak)**: the engine's `TestLocalThroughputSoak`,
  vp8channel and datachannel, each with `GOGC=10 GOMEMLIMIT=40MiB` and with
  defaults, 30 s each.
- **Android**: the x86_64 cores from the APK build under `adb shell`; then the
  debug app itself: a line imported through `ghostlane://add?url=`, the VPN
  started with the debug-only `DEBUG_START_VPN` broadcast, traffic from the
  shell through the app's tun.
- **binary size** rows for every binary measured and every `.so` in the APK.

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
| `extra` | macOS `phys_footprint` (idle, steady, lifetime peak), PSS, per-direction CPU, Go GC cycles, the limit and GC percent in force, MemoryWatch releases |
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
  through libbox's command server, and there is no hev-socks5-tunnel; a gVisor
  tun runs only in the Linux tun row.
- Loopback is not a network. Throughput is bounded by CPU, and the server, the
  page, the client and the core share the runner's cores.
- The olcRTC soak needs no meeting service, so there is no SFU, no TURN and no
  loss; the server and the client run in one process (memory and CPU cover
  two peers), with the e2e test's vp8channel options (30 fps, batch 64), not
  the app's.
- The emulator is x86_64 behind QEMU's user-mode network, not an ARM phone on
  a radio; the APK is a debug build (not minified).
- Client inbounds carry no SOCKS login; the app's Android configs carry one.
- One run per cell. Run the workflow again for a second sample.

## Files

- `lab.py`: the driver (Python 3.9+, standard library): `desktop`, `android`,
  `table`, `shapes-check`.
- `harness/`: the cores harness (`build.sh`; `cores_stub.go` and the
  `labnocores` tag exist only to vet the harness's own code without the cores).
- `loadgen/`: the page, the TLS site, the transfers, keys and certificates
  (standard library only; the same static linux/amd64 build runs on the
  runner and under `adb shell`).
- `shapes/`: the XHTTP client shapes; `xray-assets.txt`: the Xray archives
  and their sha256; `engine-src.sh`, `engine-build.sh`: the engine at the pin.
