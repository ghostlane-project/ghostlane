# olcRTC on Android without the sing-box front

**Status:** approved design (issue #33, Android scope), 2026-09-24
**Date:** 2026-09-24
**Repository:** app `ghostlane-project/ghostlane`; the engine is used as pinned (`7b78fd4a753c`), unchanged

## Problem

Since 1.0.431 (#28) the olcRTC engine routes by itself: `internal/route` takes
rules as text, `internal/sniff` reads a TLS server name or an HTTP Host, and a
matching destination is dialled from the app's own process, its name resolved
on the network's resolvers. iOS uses it. Android still starts a sing-box in
front of the engine whenever a regional bypass is on (Bypass Russia, and since
#46 Bypass Iran and Bypass China):

- **Tun mode:** hev-socks5-tunnel → sing-box front → olcRTC. The front is a
  second process that has to be started, waited for and watched: a live front
  can hide a dead engine, and every connection crosses one more local SOCKS
  hop.
- **Proxy mode:** there is no front, because the promised endpoint is the
  engine's own port, so olcRTC runs as Global and says so in the log. A user
  who chose Bypass Russia does not get it.

## Goal

On Android, olcRTC under a regional bypass runs as it does on iOS: the engine
alone, with the region's rules. Russian, Iranian or Chinese destinations
(whichever region is chosen) and the local network go straight out; everything
else, name lookups included, rides the room. This holds in tun mode and in
proxy mode. Global mode stays exactly as it is.

## Non-goals

- **Desktop.** The desktop proxy mode keeps its front. `DesktopDnsResolver`
  reads the system resolver only on Linux; on macOS and Windows it hands the
  engine `1.1.1.1`, so without the front a Russian name would be resolved by
  1.1.1.1 instead of the provider's resolver, which the front uses today
  (`DirectDns.System`). Moving the desktop needs system-resolver discovery on
  macOS and Windows first. The desktop TUN modes are untouched as well.
- **iOS.** Nothing changes there while App Review is open. iOS already runs the
  engine with the Russian rules.
- **xhttp.** Xray does not route, so its sing-box front stays on Android.
- **Engine changes.** None. `keyword:` and `regexp:` rules stay unsupported by
  `internal/route` (see Lists).

## Design

### The service (`OlcboxVpnService`)

- `startTransport`: for an olcRTC location the routing goes to `startMobile`;
  there is no `startFront` any more.
- `startMobile` computes the rule text before configuring the runtime:
  `OlcrtcDirectRules.text(region)` under `Routing.Rules`, `OlcrtcDirectRules.NONE`
  under Global.
- `configureMobileTransport` calls `olcrtc.setDirectRules(text)` on **every**
  start, the empty text included: the one `Runtime` keeps its settings across
  starts, so a Global start after a bypass one would otherwise keep the rules
  (the same reason `setUDP` is set every time). `SetDirectRules` parses the text
  at once, so a bad list fails the start with the parser's message, as on iOS.
- The front's state and code go: `startFront`, `frontsOlcrtc`,
  `FRONT_ALTERNATE_PORT`. The health check and its label return to the
  pre-front shape (commit 9a72f3e): a core port means a core, otherwise the
  engine. hev keeps pointing at the engine's port with its credentials, exactly
  as in Global.
- The log says what the engine was given: the region and the rule count, the
  way iOS logs `direct rules: N bytes`.

### Rules per region (`OlcrtcDirectRules`, `XrayGeodata`)

- `XrayGeodata` gains the Iranian and Chinese lists next to the Russian ones:
  `geosite-category-ir`, `geoip-ir`; `geosite-cn`, `geosite-tld-cn`,
  `geoip-cn`, as text in `files/xray/`, pinned by sha256 like the Russian
  three. `regional(region)` and `regionalDomains(region)` mirror `RuleSets`;
  `all` stays the Russian three (what Xray on iOS inlines), and `bundled` lists
  all eight for the hash test. An unknown region is an error, as in `RuleSets`.
- `tools/xray-geodata` writes all eight from the pinned v2fly releases
  (`dlc.dat` 20260908094002, `geoip.dat` 202609050329). Re-running it
  reproduces the three Russian files byte for byte (checked 2026-09-24), so the
  pins do not move. `tools/xray-geodata.lock` records all eight.
- `OlcrtcDirectRules.text(region)` = the private ranges, the region's name
  rules, the region's IPv4 prefixes. It keeps only the rule shapes the engine
  parses (`domain:`, `full:`, addresses, prefixes) and drops `keyword:` and
  `regexp:`. Today that is three lines of `geosite-cn` (two AWS DNS name
  patterns and one Akamai host); those names ride the tunnel. The Russian and
  Iranian lists have none. The no-argument `text()` stays the Russian text iOS
  uses.

Sizes, for the memory budget (plus the 13 private ranges each): Russia 1103
names + 12819 prefixes (what iOS already runs), Iran 190 + 2028, China 6603
(6554 + 49 TLDs) + 8348.

### UDP (`OlcRtcUdpRelay`)

The relay is off today on a datachannel room without a datagram lane (Telemost
and Jitsi), so the engine answers every UDP ASSOCIATE with "host unreachable".
With the front, a regional UDP flow (QUIC to a Russian site, a VK call) never
reached the engine: the front sent it direct. Without the front it would be
refused.

So the relay is on whenever direct rules are on: `enabled(provider, transport,
directRules)`. The pinned engine (olcrtc#49) takes an association on a link
without the lane "for what it carries off the lane": DNS for a covered name on
the network's resolvers, other DNS over the reliable stream, a covered target
from a socket of this process. What is for the lane is dropped at once, before
it takes a flow, and the association ends with its control connection. That is
what the front did to tunnel-bound UDP on these rooms (its SOCKS outbound was
refused, so it dropped the packets). Global keeps the relay off there: a fast
refusal, as today.

Limit that comes with it: every SOCKS client, TCP or an association's control
connection, counts against the engine's `maxSocksConns` (512). hev keeps one
association per local UDP socket (full-cone), not per destination, and iOS, WB
Stream and SaluteJazz already run this way.

## Behaviour changes a user sees

- Android proxy mode: Bypass now applies to olcRTC locations (it was Global).
- Android tun mode: the same routing as before, from one process instead of
  two. Direct names are resolved on the engine's resolver list (the network's
  resolvers, then the public operator: `UpstreamDns`) instead of the first
  network resolver the front was given.
- Regional UDP on Telemost/Jitsi datachannel rooms still goes direct.

## Testing

- `OlcrtcDirectRulesTest`: every line of every region is a shape the engine
  parses; each region carries the private ranges and its own lists and none of
  another region's; `regexp:`/`keyword:` are dropped; an unknown region fails.
- `XrayGeodataTest`: all eight files hash to their pins; each region's lists
  have the expected scale; the Russian `all` is unchanged.
- `OlcRtcUdpRelayTest`: rules on turn the relay on for Telemost and Jitsi
  datachannel; rules off keeps every current answer.
- CI (`pr-checks`): JVM tests, Android compilation, Apple Kotlin compilation
  (iosMain reads `XrayGeodata`), `sing-box check` (unchanged shapes).
- On a device, Android tun and proxy mode, a Telemost room, each region the
  tester can judge: `yandex.ru/internet` shows the carrier's address,
  `whatismyip.com` the exit; the log shows the rules line and no
  `sing-box front`; a VK call connects.

## Rollout

A separate PR after #46, and its own release: the Android data path changes
for every olcRTC user with a bypass on, so it waits for the device checks
above.
