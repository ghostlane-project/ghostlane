# iOS: one Go runtime per tunnel extension

Status: in progress. Proposed 2026-09-13 after the 1.0.423 speed-test deaths;
part 1 (hev in front of Xray and olcRTC) landed 2026-09-14, unreleased and
not yet run on a device. Parts 2 (Xray's h2 receive window) and 3 (Bypass
Russia in Xray's rules) are separate branches. Since 2026-11-13 sing-box's
own lines run behind hev too (below, "sing-box behind hev"), unreleased and
not yet run on a device.

## What landed (part 1)

- `scripts/hev-pins.sh`, `scripts/build-hev-ios.sh`,
  `.github/workflows/hev-tunnel.yml`, `scripts/fetch-hev-ios.sh`: the
  framework is built once per pin on a macOS runner and published as
  `HevSocks5Tunnel-ios.zip` at tag `hev-socks5-tunnel-<12hex>-b<N>`
  (since 2026-11-14 `…-b<N>-l<8hex>`, the last part the digest of the lwIP
  patch, below); every other build downloads it, like Cores.
- Xcode: the PacketTunnel target links `-lhev-socks5-tunnel` from the
  xcframework slice (LIBRARY_SEARCH_PATHS per SDK) and imports the module
  from the slice's `Headers/HevSocks5Tunnel` (SWIFT_INCLUDE_PATHS per SDK);
  the "Fetch Core Frameworks" phase fetches it.
- `HevTunnel.swift` (start on a thread, quit, stats, the YAML),
  `TunDescriptor.swift` (the descriptor lookup shared with libbox), and
  the provider: on xhttp and olcRTC the engine starts on its SOCKS port,
  then hev takes the tun; no sing-box service. Reality and Hysteria2 are
  unchanged.
- DNS on those paths: the tun advertises OpenDNS (208.67.222.222,
  208.67.220.220), reached through the SOCKS engine as UDP. 172.19.0.2 was
  an address only sing-box answered.

## hev's memory (2026-11-14)

What hev holds in the extension, cut on two sides; the arithmetic is in the
comments of each file, and `scripts/test-ios-hev-config.sh`
(HevTunnelConfigurationTests, in the ios-extension job) holds them together.

- `HevTunnel.swift`, `misc`: `udp-copy-buffer-nums: 2`, so the 24,576-byte
  task stack written is the one hev uses. Without it hev's ten 1,500-byte UDP
  buffers put its floor at 35,480 and it raised the stack to that without a
  word: 64 KiB mapped per session instead of 48. `max-session-count` 768 ->
  256 (an LRU cap: at most 255 live, the least recently active ended).
- `scripts/patches/hev-lwipopts-ios.patch`, applied by `build-hev-ios.sh`
  (a `-D` cannot work: hev's lwipopts.h defines every option without
  `#ifndef`): lwIP's static pools, all written at start, from 1,871,744 to
  468,752 bytes (pcbs, segments and pbufs for 256 sessions; the unused
  PBUF_POOL to lwIP's minimum), and TCP_WND = TCP_SND_BUF = 4 x MSS
  (32,764) from 8 x MSS, which halves what a stalled upload holds per TCP
  session (the window, in 9 KiB pbufs). The send buffer saves nothing: hev
  writes a download without copying from its 4 KiB ring.
- The tag carries the patch's digest, so the published framework changes with
  it. Until the new tag is published (the `hev-socks5-tunnel` workflow,
  dispatched from main with publish on), PR Checks builds it from the pin in
  the ios-extension job, and a release build's fetch phase fails naming the
  tag.

## What the user has to do

1. Run the `hev-socks5-tunnel` workflow once (publish = true) so the tag
   exists; until then the extension's fetch phase fails with a message
   naming the tag.
2. Open the project in Xcode and check, for the PacketTunnel target:
   `LIBRARY_SEARCH_PATHS[sdk=iphoneos*]` and `[sdk=iphonesimulator*]`,
   `SWIFT_INCLUDE_PATHS[sdk=…]`, and `-lhev-socks5-tunnel` in
   `OTHER_LDFLAGS`. The Linux typecheck cannot see the module map; the
   first device build is what proves `import HevSocks5Tunnel` resolves.
3. On the device, an xhttp session's diagnostics must read
   `tun up: engine=xray+hev …`, names must resolve, and a speed test must
   complete; the memory trace says what the extension holds without
   gVisor. Then olcRTC the same way, with attention to DNS latency on the
   relay (`SOCKS5 UDP associate` lines in olcrtc.log are the queries).

## Open decisions

- **olcRTC DNS - decided.** 1.0.426 and 1.0.427 kept olcRTC behind
  sing-box because with hev the resolver's queries would ride the relay's
  datagram lane, which loses packets under load. Engine aac553b8 answers a
  port-53 datagram over a smux stream instead (TCP DNS, RFC 7766, to the
  resolver the tun advertises; the server dials it like any CONNECT; 64 in
  flight, 5 s each, the lane as fallback), so from 1.0.428 olcRTC sits
  behind hev too and the extension runs one Go engine on every path except
  sing-box's own transports. If a device shows resolver timeouts on olcRTC,
  the way back used to be one predicate (`hevOwnsTun`). Since 2026-11-13 the
  extension decides in `TunArrangement.decide` and has no arrangement with
  sing-box's tun in front of another engine any more; going back would add
  one, starting olcRTC before the sing-box tun the app still writes as
  config.json beside olcrtc.json.
- **Bypass Russia - decided (olcbox#28).** On xhttp it is done inside Xray
  (part 3). On olcRTC there was no router, so the mode was Global there
  from 1.0.428. Rather than put Xray in front of olcrtc (a second Go engine,
  ~5-10 MB), the olcrtc client carries the rules itself since engine
  b9dc3a19: the app hands it the same three lists as direct rules
  (`OlcrtcDirectRules`, `MobileRuntime.setDirectRules`); a matching name or
  address is dialed from the engine over the pinned interface, a name the
  tun hides behind an address is read off the TLS hello or the HTTP Host,
  a matching name's DNS query goes to the network's own resolver, matching
  UDP is relayed by the engine, everything else rides the room. Design:
  `docs/superpowers/specs/2026-09-15-olcrtc-client-routing-design.md`.
- **max-session-count 768** is Tun2SocksKit's figure, not the README's
  1200; each live session holds a task stack.

## The finding

An `NEPacketTunnelProvider` gets about 50 MB. In the 1.0.423 export the three
transports split cleanly by how many Go engines the extension was running:

| transport | engines in the extension | footprint | outcome under Ookla |
|---|---|---|---|
| Hysteria2 (and Reality) | sing-box | 24.7 MB | survived |
| xhttp | sing-box (tun, gVisor) + Xray (SOCKS) | 46.8 MB, 1117 goroutines | killed at 33 s |
| olcRTC | sing-box (tun, gVisor) + olcrtc (SOCKS) | 40 MB idle, 48 MB upload peak | killed |

sing-box alone is fine. The two transports that die are the two where sing-box
is only there to own the tun and hand every connection over a local SOCKS hop
to a second Go engine, so every connection is buffered twice (gVisor endpoint
plus the second engine's pipes) and served by twice the goroutines, inside one
runtime that was sized for one engine.

Every Xray-based iOS client that passes a speed test in the same 50 MB — Happ,
V2Box, Streisand, FoXray — runs Xray plus a small C tun2socks,
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT, lwIP,
`./build-apple.sh` produces `HevSocks5Tunnel.xcframework`; on iOS through
[Tun2SocksKit](https://github.com/EbrahimTahernejad/Tun2SocksKit)). Its
low-memory profile is `tcp-buffer-size: 4096`, `task-stack-size: 24576`,
`max-session-count: 1200`: kilobytes per connection, not the 32–128 KB gVisor
holds per endpoint plus a goroutine.

## The change

Keep sing-box only where it is the transport. In front of Xray (xhttp) and
olcrtc (olcRTC) put hev-socks5-tunnel instead:

```
today:   tun → sing-box/gVisor → SOCKS 127.0.0.1:10810 → Xray / olcrtc → exit
after:   tun → hev-socks5-tunnel (C) → SOCKS 127.0.0.1:10810 → Xray / olcrtc → exit
```

1. **Build.** Add `HevSocks5Tunnel.xcframework` to the iOS Frameworks
   workflow next to `Cores.xcframework` (`./build-apple.sh` on the macOS
   runner; pin the commit like the three Go pins in `scripts/cores-pins.sh`).
   Cores keeps all three Go engines linked; only what is started costs memory.
2. **Extension.** For xhttp and olcRTC, `startEngine` starts the SOCKS engine
   as now, then `hev_socks5_tunnel_main_from_str(config, tunFd)` on its own
   thread instead of `startOrReloadService`. The tun fd is the one libbox
   already finds (the utun control-socket scan in sing-tun's `tun_darwin.go`;
   Tun2SocksKit does the same). Stop = `hev_socks5_tunnel_quit()`.
3. **DNS and routing.** sing-box did DNS and the Bypass Russia rules. With
   hev in front there is no router in the path: Xray takes over (its
   `routing` with geoip/geosite, its `dns` object, a `freedom` outbound for
   the direct set) — the existing `XrayConfig.buildXhttp` grows the same rule
   set `SingBoxConfig` renders today. olcRTC has no router of its own; Global
   mode works as is, Bypass Russia over olcRTC needs either Xray as the
   router in front of olcrtc's SOCKS (two Go engines again, but no gVisor)
   or stays on sing-box. Decide with a measurement, not by reasoning.
4. **UDP.** hev relays UDP through SOCKS5 UDP ASSOCIATE, which both Xray's
   SOCKS inbound and olcrtc's SOCKS server already speak (the app relies on it
   today through sing-box). `udp: 'udp'`.
5. **Memory check.** The same export as always: `MemoryWatch` idle and under
   Ookla, both directions. Expected: xhttp near the Hysteria2 figure plus
   Xray's own ~10 MB; olcRTC's 40 MB idle loses gVisor's share.

What this does not touch: Android and desktop (VpnService and the desktop
bridge have no 50 MB limit; they keep sing-box), the link format, the
coordinator, the fleet.

## sing-box behind hev (2026-11-13)

Reality, Hysteria2 and WebTunnel (VLESS over ws or httpupgrade, with TLS)
were the last lines on sing-box's own tun: sing-box owned the descriptor
through libbox and ran gVisor. They now run like xhttp and olcRTC:

```
before:  tun → sing-box/gVisor (tun inbound) → Reality / Hy2 / ws → exit
after:   tun → hev-socks5-tunnel (C) → SOCKS 127.0.0.1:10810 → sing-box → exit
```

Phase 0 of the plan the owner approved on 2026-11-13; the other half of
Phase 0 trims sing-box's build tags for Android and the desktops.

**Why.** The lab (GitHub runners, branch `lab/core-footprint`,
`lab/footprint/lab.py`) ran sing-box Reality on Linux both ways: with hev in
front of a SOCKS inbound the Go runtime held 17.6 MB (`sys` minus released),
with sing-box's own gVisor tun 38.2 MB; hev's resident set was 6.5 MB. About
20 MB less in an extension that dies at 46.0 to 47.5 MB of footprint. With
this change's own configs (run 43541302148, the peak of a 32-connection
burst): Reality 38.2 to 17.4 MB, Hysteria2 52.7 to 17.3, Bypass Russia
Reality 38.7 to 19.0; CPU per GB the same, upload the same, a single
download on loopback 689 against 1735 Mbit/s (a phone's link is far below
both).

**What changed.**

- `SingBoxConfig.buildIosSocks` (shared Kotlin): the iOS shape with a SOCKS
  inbound on 127.0.0.1:10810 that demands a login and keeps an idle UDP
  association for 30 s (`udp_timeout`, hev's own UDP timeout; Android's
  inbound keeps sing-box's five minutes), and nothing else changed.
  One renderer (`renderIos`) writes both iOS shapes, the inbound its only
  argument that differs, so DNS, the outbounds, the route and the cache
  file cannot drift from `buildTun`. `IosVpnManager` writes it for every
  line that is not xhttp, with the same login Xray's inbound gets
  (`SocksLogin`); the app's log says `engine=sing-box+hev`. One constant,
  `SING_BOX_BEHIND_HEV`, picks both the shape and that label.
- `TunArrangement.swift` (extension, Foundation only): one decision per
  start from config.json, xray.json and olcrtc.json: `singBoxTun`,
  `singBoxBehindHev(socks)` or `engineBehindHev(engine, socks)`. olcrtc.json
  wins, then xray.json; otherwise a SOCKS inbound in config.json means
  sing-box behind hev, and a tun inbound (or a config that does not parse)
  means sing-box on the tun. The tun's resolvers and the start path both
  come from the answer. The port and login are read from sing-box's shape
  (`inbounds[]` of `type` socks: `listen_port`, `users[0]`), Xray's from its
  own (`accounts[0].user`/`pass`).
- `PacketTunnelProvider`: for `singBoxBehindHev` the libbox setup is as
  before, the command server is built with the platform as before and
  `startOrReloadService` starts sing-box; then the Go memory limit (after
  the service, the path's only engine), then hev on the tun. A failure after
  the service started closes the service and the server. Diagnostics read
  `tun up: engine=sing-box+hev socks=127.0.0.1:10810 fd=<fd> dns=172.19.0.2`.
  On a network change sing-box is still told (`resetNetwork`): hev keeps its
  tun, the connections sing-box closes end at hev, and the apps redial. Xray
  and olcRTC behind hev are left to re-dial as before. Stop is hev first,
  then the service.
- Tests: `SingBoxConfigTest` (one SOCKS inbound on loopback with the login;
  everything else equal to `buildTun`'s, Global and Bypass, Reality, gRPC,
  Hysteria2 and WebTunnel), `SingBoxConfigDumpTest` with reference files
  `ios-socks-reality`, `ios-socks-hysteria2`, `bypass-ios-socks-reality`
  and `bypass-ios-socks-hysteria2` (started by
  `scripts/check-singbox-configs.sh` with the cache file in the build
  directory), and `iosApp/Tests/TunArrangementTests.swift`
  (`scripts/test-ios-tun-arrangement.sh`, run from
  `scripts/test-ios-room-list.sh` in both CI jobs that run it), which also
  reads those reference files.

**DNS.** Unchanged for the system: the tun still advertises 172.19.0.2,
which only sing-box answers. hev hands every datagram to sing-box as SOCKS5
UDP (`udp: 'udp'`), the route's `sniff` and `hijack-dns` on port 53 catch the
query whatever the inbound, and sing-box answers with a fake address
(198.18.0.0/15) or, under Bypass Russia, resolves the Russian lists on the
network underneath. A connection to a fake address reaches sing-box over
SOCKS as an IP, and sing-box turns it back into its name from the fake-IP
store (`experimental.cache_file`), as it did on the tun.

**IPv6.** Carried as before. hev's lwIP takes IPv6 TCP and UDP for any
address, as it takes IPv4 (it needs no IPv6 address of its own for that),
and hands it to sing-box as an IPv6 SOCKS address. sing-box routes it as
it did on gVisor: through `out`, or under Bypass Russia `direct` for a
Russian or private address. Under Global the fake addresses are IPv4 only,
so IPv6 comes from literals; under Bypass Russia the Russian names are
resolved for real and get real AAAA answers, so a dual-stack Russian site
is reached over IPv6, directly, on a network that has it. hev accepts a
connection before sing-box dials it. Through the exit that changes nothing,
since the exit dialed after the connect on the tun too, so an exit without
IPv6 shows as a reset either way. A direct connection under Bypass Russia
that cannot be made is now a reset after the connect, where gVisor refused
it at once. sing-box's own sockets are not pinned to the physical interface
(no iOS config sets `route.auto_detect_interface`); only olcRTC's are.

**What it costs.** Two things sing-box's tun did are gone on these lines,
as xhttp and olcRTC lost them when hev went in front of those (1.0.426,
1.0.428):

- A TCP connection with no bytes either way for 60 s is closed by hev
  (`read-write-timeout: 60000` in `HevTunnel.configuration`; hev's own
  default is 300 s). sing-box on its tun had no idle limit. An SSH session
  without keepalives, a long poll or an idle HTTP/2 connection is closed
  after a minute and the app redials. Kept at 60 s on purpose: it is what
  the xhttp and olcRTC paths were measured with, and every idle session
  kept longer holds hev's task stack and the engine's buffers. If the
  device check shows a cost, `tcp-read-write-timeout: 300000` beside the UDP
  line raises it for every hev path, and the memory trace has to be taken
  again.
- ICMP no longer reaches sing-box. On the tun, sing-box sent a ping to an
  outbound that can carry it, which only `direct` can, so under Bypass
  Russia a ping to a LAN or Russian address was answered; under Global it
  never was. hev forwards no ICMP (`tunnel.icmp` would only have hev answer
  every ping itself), so ping gets no answer on any line.

UDP keeps hev's 30 s idle limit, and sing-box's inbound now has the same, so
an association hev has ended does not hold its server connection for five
minutes in sing-box.

**A new flow is not slower on the phone.** Every new UDP association or TCP
connection behind hev starts with a SOCKS5 handshake on loopback. On Linux,
hev at our pin (hev-socks5-core f5c97b0, inside 941c758) waits about 0.42 s
for it, because it sends the method offer and the login with `MSG_MORE` and
Linux holds each of those writes for about 200 ms. Darwin has no `MSG_MORE`:
hev falls back to 0, and the Mac's and the iPhone SDK's `<sys/socket.h>`
have none. The lab measured it both ways (`lab.py udp-flow`, runs 43538233589
and 43541302148):
- On the macOS runner behind hev, a new name takes 0.020 s against 0.014 s
  on sing-box's gVisor tun, and the first byte of a new connection arrives
  in 0.0036 s against 0.0033 s.
- On Linux, hev built without `MSG_MORE` matches the gVisor tun.

Android and the desktop build their own hev 2.14.4, which has no `MSG_MORE`.
Upstream fixed this in 2.20.0 (b43fa85); moving the pin needs its own memory
run.

**The way back.** `IosVpnManager.SING_BOX_BEHIND_HEV = false`: the app then
writes `SingBoxConfig.buildTun` (same arguments, no login) and logs
`engine=sing-box`. The extension needs no change: `TunArrangement.decide`
reads the tun inbound and answers `singBoxTun`, which starts sing-box on the
tun with `Tun.dns`, exactly the path 1.0.472 runs.

That is the way back for a new build. A phone that goes back to a build from
before this change (a TestFlight tester returning to the App Store build)
is another matter. Its extension does not read the inbound: with no
xray.json and no olcrtc.json it starts sing-box on whatever config.json is
left in the App Group, and this change left a SOCKS shape there. sing-box
starts, nothing reads the tun, and the phone shows Connected and carries
nothing. The old app writes a tun shape on its next connect, so a reconnect
from the app ends it. Until then a start the app did not make (on-demand
under the kill switch, the switch in Settings) comes up dead: the tun takes
every route and nothing reads it, so the phone has no network, and with the
kill switch on, on-demand brings it back up that way. Tell testers to
reconnect from the app after going back, and say so in the release notes of
the build that ships this.

**What the device check must show**, for Reality and for Hysteria2:

1. The diagnostics line `tun up: engine=sing-box+hev …` and the app's log
   `engine=sing-box+hev`.
2. The MemoryWatch trace idle and under an Ookla speed test, both
   directions: the footprint well under the old Reality figure, `sys` minus
   `rel` near the lab's, and no kill.
3. Names resolve (Safari, an app that resolves on its own), and nothing
   waits on DNS.
4. Bypass Russia: a Russian site leaves directly (2ip.ru shows the phone's
   own address), everything else through the exit.
5. Kill switch on: the tunnel comes up and carries traffic, and nothing
   leaves around it while it reconnects.
6. Wi-Fi to cellular and back with a download running: the diagnostics show
   `network changed; hev keeps its tun, sing-box resetNetwork`, connections
   come back without a reconnect of the tunnel.
7. Idle connections: an SSH session with no keepalives (or a WebSocket
   page that sends nothing) left idle for 90 s is closed at about 60 s, as
   decided above; a messenger left idle for five minutes still delivers a
   message at once, and a push notification still arrives. If either of the
   last two fails, raise the TCP timeout (above) and take the memory trace
   again.
8. IPv6, on a network that has it: an IPv6 literal (for example
   `http://[2606:4700:4700::1111]/`) under Global goes to the exit, and
   opens as it did on 1.0.472 (an exit without IPv6 fails it either way);
   under Bypass Russia a dual-stack Russian site opens directly over IPv6
   (an IPv6 check on a Russian site shows the phone's own address), and
   everything else through the exit.
9. Ping: no answer on any line, under either routing (expected, see above).

## Why not Rust

The Rust equivalent is [tun2proxy](https://github.com/tun2proxy/tun2proxy)
(smoltcp, iOS FFI). It would be a second engine of our own to carry, with
far fewer iOS deployments behind it than hev-socks5-tunnel has, for the same
result. The wheel exists; this plan uses it.
