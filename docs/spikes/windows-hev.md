# Windows tun spike: hev-socks5-tunnel with Wintun

Question: can hev-socks5-tunnel, the C tun the phones and Linux already use,
bring a Wintun adapter up on a GitHub-hosted Windows runner and carry a TCP
fetch and a UDP datagram to a SOCKS5 server on loopback?

Verdict: **yes, with a different build than the one first planned.**

| Build of hev | Result on the runner |
|---|---|
| Upstream release 2.20.0, the published `win-x64` zip (sha256 pinned) | green: TCP fetch and UDP echo through the tun |
| Upstream tag 2.20.0 built on the runner with clang for MSVC | green: the same |
| The tree vendored under `androidApp/src/main/jni/hev-socks5-tunnel`, built under MSYS2 (the Makefile's msys branch) | builds and brings the adapter up, **carries nothing**: every SOCKS5 connection stalls after the handshake |

The workflow is `.github/workflows/rust-windows-lab.yml`. It runs by dispatch
and on pushes that touch it or this page. The MSYS2 job is a control that is
expected to stay red (`continue-on-error`), so the run is green when both
native variants are.

## What the job does

1. Starts a SOCKS5 stub on 127.0.0.1:1080 (a Python script written by the job).
   It answers CONNECT with a fixed HTTP 200 body and UDP ASSOCIATE with an echo,
   and logs every destination it is asked for.
2. Downloads Wintun 0.14.1, checks the zip's sha256
   (`07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51`) and
   the Authenticode signature of `wintun.dll` (valid, signer WireGuard LLC).
3. Gets `hev.exe` (one of the three builds above), copies `wintun.dll` beside it.
4. Starts `hev.exe config.yml`: adapter `hevtun`, IPv4 `198.18.0.1`, SOCKS5
   server `127.0.0.1:1080`, `udp: udp`.
5. Adds one route by hand and fetches `http://203.0.113.10:8080/` (TEST-NET-3,
   an address nothing on the runner owns) and sends one UDP datagram to
   `203.0.113.10:5300`. The test passes only if the body and the echo come
   back **and** the stub's log names exactly those destinations.
6. Kills `hev.exe` with `taskkill /F` and lists the adapters.

Stub, hev and test run in one step on purpose: a process started in a step
does not survive the step on these runners (the first rounds lost both).

## Evidence

Failing test first: the first push ran the fetch with no tun at all and failed
with the HttpClient timeout (run 44939584899).

All three builds, one run: 44941939345 (the final workflow, one job per build).

- **Runner**: Windows Server 2025 (10.0.26100), image `windows-2025-vs2026`
  version 20261116.315.1. The step runs as administrator. The kernel driver was
  not refused: Wintun created the adapter (`Socks5 Tunnel`, driver
  `wintun.sys`, status Up) with no setup beyond having the DLL beside the
  program. No driver install, no test signing.
- **Native build (clang 20.1.8 for MSVC, GNU Make 4.4.1)**: imports are
  `KERNEL32.dll`, `WS2_32.dll`, `IPHLPAPI.DLL`, `ADVAPI32.dll` and nothing else.
  The bundle is two files: `hev.exe` and `wintun.dll`. No `msys-2.0.dll`. The
  release zip holds exactly those two files (`hev-socks5-tunnel.exe` 516,608
  bytes, `wintun.dll` 427,552 bytes, the same sha256 as the DLL from the
  signed Wintun release, `E5DA8447DC2C320E...3220DAFCE`).
- **Test output** in both native jobs: `TCP fetch: 200 hev-spike-tcp-ok`,
  `UDP echo: echo:ping-udp`, stub log `CONNECT 203.0.113.10:8080` and
  `UDP 203.0.113.10:5300 8`.
- **MSYS2 build** (gcc 16.2.0, make 4.4.1, msys2-runtime 3.6.12): builds
  without a flag change once the tree is copied with `MSYS=winsymlinks:native`
  (the tree holds symlinks, and a plain `cp -r` fails on them). `hev.exe`
  imports `IPHLPAPI.DLL`, `KERNEL32.dll`, `WS2_32.dll` and `msys-2.0.dll`;
  `ldd` finds `msys-2.0.dll` (3.3 MB) and system DLLs only, so
  `msys-2.0.dll` is the one DLL it needs beside it. It creates the adapter and
  sets the address, and the OS then sends packets into the tun and hev
  accepts the flows (`socks5 client tcp -> [203.0.113.10]:8080`), but
  every connection to the stub stops after hev writes the method list: the
  stub answers, hev's read never completes. Tried and still stuck: `-O0`,
  `pipeline: true`, a 0.2 s reply delay, the stub on the host's own address
  instead of loopback. An `strace` of the process shows `recv` on the
  connected socket returning EAGAIN again and again for 28 s while the stub
  had logged its reply. The vendored tree's Windows reactor registers each
  socket with `WSAEventSelect` on the handle behind a Cygwin file descriptor,
  which a recent Cygwin runtime also manages; that is the likely cause, but
  it was not isolated.

## What the job had to make by hand

- **One route**: `New-NetRoute -DestinationPrefix 203.0.113.0/24
  -InterfaceAlias hevtun -NextHop 0.0.0.0` (on-link, because Wintun is a
  layer 3 device). hev sets the adapter's address with a /32 prefix and adds no
  route of its own. `Find-NetRoute` for the test address picks `hevtun`.
- **Address**: none by hand; hev set `198.18.0.1/32` itself.
- **DNS**: none. The adapter came up with no IPv4 DNS server and the three
  default site-local IPv6 servers (`fec0:0:0:ffff::1` to `::3`). Name
  resolution through the tun, an NRPT rule and a DNS guard were not tested.
- **MTU**: the config asked for 1500; the adapter reported 65535 in both the
  MSYS2 and the native build. Not investigated.
- **Not tested**: a default route through the tun, the bypass route for the
  upstream server, an engine leaving by `IP_UNICAST_IF`, IPv6, throughput,
  and a second adapter.
- **After `taskkill /F`** the adapter is no longer listed and the route is
  gone: Wintun removes a non-persistent adapter when its owner dies. The
  sweeper in the plan is still worth having for a crash between the route
  being added and the process starting.

## Consequences

- The Makefile's msys branch in the vendored tree is a dead end for now.
  Upstream's own Windows build moved to clang for MSVC by 2.20.0 (its CI
  builds `win-x64`, `win-x86` and `win-arm64` that way and ships them without
  a Cygwin DLL). The tree the app vendors predates that change, so a Windows
  bundle should use a separate pin of upstream (tag 2.20.0, commit
  `75dbc1e9ace6c70d3d1b94df7c283efe1de7effc`) rather than the one the phones
  use, or the shared pin moves to 2.20.0.
- Smaller bundle than planned: two files, no Cygwin runtime.
- The adapter is created without any signing or driver step, so the tunnel
  does not need an installer for a kernel driver; the app only needs to be
  elevated.

## Not answered here

- Whether BoringSSL builds for MSVC and how far the Rust workspace compiles
  for Windows were answered by that workspace's own CI and are not rebuilt in
  this job: on `windows-2025` with MSVC, `boring-sys` builds, and `cargo check`
  of the shipped crates stops at 7 errors in two crates (`olc-net` 5, the
  vendored `gotatun` 2), so nothing downstream of them was reached.
- What is pinned: Wintun (version and sha256), the hev release zip (version and
  sha256), the hev tag (commit checked), the two actions (tags). Not pinned by
  hash: the MSYS2 packages (current at the time), Make from Chocolatey (version
  only) and the LLVM and Visual Studio on the runner image.
