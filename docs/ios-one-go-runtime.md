# iOS: one Go runtime per tunnel extension

Status: in progress. Proposed 2026-09-13 after the 1.0.423 speed-test deaths;
part 1 (hev in front of Xray and olcRTC) landed 2026-09-14, unreleased and
not yet run on a device. Parts 2 (Xray's h2 receive window) and 3 (Bypass
Russia in Xray's rules) are separate branches.

## What landed (part 1)

- `scripts/hev-pins.sh`, `scripts/build-hev-ios.sh`,
  `.github/workflows/hev-tunnel.yml`, `scripts/fetch-hev-ios.sh`: the
  framework is built once per pin on a macOS runner and published as
  `HevSocks5Tunnel-ios.zip` at tag `hev-socks5-tunnel-<12hex>-b<N>`; every
  other build downloads it, like Cores.
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

- **olcRTC DNS.** Plain DNS over the datagram lane on a lossy relay was the
  reason the hijack existed. If it is too slow, the cheapest way back is to
  keep sing-box for olcRTC only: `PacketTunnelProvider.hevSocks` returns
  nil for the olcRTC case and everything else follows.
- **Bypass Russia** on these two paths needs a router in front of the engine
  or rules inside it (part 3). Until then the mode is effectively Global
  there: hev forwards everything to the SOCKS port.
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

## Why not Rust

The Rust equivalent is [tun2proxy](https://github.com/tun2proxy/tun2proxy)
(smoltcp, iOS FFI). It would be a second engine of our own to carry, with
far fewer iOS deployments behind it than hev-socks5-tunnel has, for the same
result. The wheel exists; this plan uses it.
