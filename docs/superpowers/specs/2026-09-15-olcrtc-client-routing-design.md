# Bypass Russia inside the olcRTC client

**Status:** approved design (olcbox#28, option 3), implemented alongside this document
**Date:** 2026-09-15
**Repositories:** engine `ghostlane-project/olcrtc` (branch `proofkit`), app `romanpodpriatov/olcbox`

## Problem

Since 1.0.428 olcRTC on iOS runs behind hev-socks5-tunnel: one Go engine in the
extension, a C tun2socks in front of its SOCKS port. hev has no router and the
engine has no rules, so the Bypass Russia mode is Global on olcRTC locations —
everything, Russian addresses included, rides the tunnel, while the same switch
works on xhttp (Xray carries the rules and the DNS split inline).

olcbox#28 lists three options. The second (Xray as a router in front of olcrtc)
is a second Go engine in a 50 MB extension again. The third — rules in the olcrtc
client, a direct dial for matches instead of a smux stream — is the one chosen:
it costs a few hundred kilobytes and adds no engine.

## Goal

With Bypass Russia on, an olcRTC location on iOS behaves as an xhttp one does:
Russian names and addresses and the local network go straight out through the
physical interface, resolved by the network's own resolver; everything else,
name lookups included, rides the room. Global mode and every configuration that
does not set rules behave exactly as before, byte for byte on the wire.

## Non-goals

- Android and desktop keep their sing-box front in front of olcRTC. The engine
  rules work there too (hev's `mapdns` and browsers hand the engine names), but
  replacing the fronts is a separate change with its own device checks.
- Sniffing QUIC. UDP is routed by address (and by name where the SOCKS client
  gives one); an HTTP/3 connection to a Russian name on a non-Russian address
  takes the tunnel, as it does today.
- Lists in the engine. The engine takes rules as text; what the lists contain
  stays the app's decision (`XrayGeodata` and `XrayConfig.PRIVATE_RANGES`).

## Architecture

### Engine: `internal/route`

A leaf package. `Parse(text string) (*Rules, error)` reads one rule per line,
in the syntax the app's Xray lists already use, and nothing else:

| line | meaning |
|---|---|
| `domain:example.ru` | the name and every name under it |
| `full:api.example.ru` | that name only |
| `1.2.3.0/24`, `2001:db8::/32` | an address in the prefix |
| `1.2.3.4` | that address |
| blank, `# …` | skipped |

Any other line is an error naming the line number; a typo in a bundled list
fails the start rather than silently shipping a shorter list. Names are
lowercased with the trailing dot removed; no IDNA conversion (lists, SOCKS
names and TLS server names are all punycode already). An empty text yields
`nil`, which every consumer treats as "off".

Matching: `MatchDomain(name)` walks the name's suffixes (`a.b.c.ru` → `b.c.ru`
→ `c.ru` → `ru`) against a set of suffixes, plus a set of exact names — at
most a handful of map lookups. `MatchIP(netip.Addr)` binary-searches a sorted
list of address ranges built from the prefixes, overlapping and adjacent
ranges merged at parse time; IPv4-mapped IPv6 is unmapped first.
`MatchHost(host)` picks by whether the host parses as an address. `Summary()`
is the one line the log prints at start ("1103 names, 12832 prefixes").

Memory for the app's lists (≈1100 names, ≈12800 IPv4 prefixes): under 300 KB.

### Engine: `internal/sniff`

`Host(head []byte) (host string, need bool)`: the TLS server name from a
complete ClientHello record, or the `Host` of an HTTP/1 request line, from the
first bytes of a connection. `need` reports that the decision is still open —
a TLS record header announcing more bytes than were seen, an HTTP request
whose headers have not ended — so the caller reads on. Not TLS and not HTTP,
a ClientHello that spans records, a missing extension: `""` and `need=false`.

Pure functions, no dependencies, tested against ClientHellos produced by
`crypto/tls` itself.

### Engine: the client

`internal/client.Config` gains `Direct *route.Rules`. The `Client` keeps the
rules and a `protect.Dialer` over the session's resolver: direct sockets are
protected exactly like the provider's own — on iOS pinned to the physical
interface, on Android passed to `VpnService.protect` — and names dial through
the same ring the engine already resolves its carrier with, the network's
resolvers first.

**CONNECT.** Before the session is waited for:

1. The target is a name: `MatchDomain` → direct, else the tunnel. No sniffing:
   a name is already the best evidence there is.
2. The target is an address: `MatchIP` → direct. Otherwise, with rules on, the
   SOCKS success reply is written first and the first bytes are read (300 ms,
   at most 16 KB, a pooled buffer): a server name that matches → direct,
   dialed **by that name** so the network's resolver decides the address
   (falling back to the address the client gave when the lookup fails); no
   name, or a name the rules do not cover → the tunnel, with the bytes already
   read replayed into the stream after the CONNECT ack. Rules off: the path
   before this change, unchanged.

Consequence of replying first on the sniffed path: a CONNECT that then fails
closes the connection instead of answering a SOCKS error. hev turns either into
a reset toward the app.

A direct connection never waits for the session: Russian sites keep working
while the room reconnects. `tunnel` grows a `head` to replay and a flag saying
whether the reply is already on the wire; nothing else about it changes.

**UDP.** In `forwardLocalUDP`, after the DNS check and before the lane:
`MatchHost(target)` → a direct flow: a connected, protected UDP socket to the
target (a name resolves through the ring), a goroutine reading its answers
back into the association as SOCKS UDP datagrams, the same read buffer size
the associations use (16 KB on the phone profile). Flows are keyed like the
lane's — (association, client, target) — swept by the same idle sweeper on the
same timeout, closed with their association, and capped by the same flow limit
so the direct table cannot grow past what the lane's already may.

**DNS.** A port-53 datagram is parsed for its first question; with rules on
and the name matching, the query goes to the network's resolver over a
protected UDP socket — `(*protect.Resolver).Exchange`, which walks the ring
exactly as lookups do (preferred server first, silence demotes it) — and the
answer comes back through the association untouched: real addresses, real
TTLs, every record type. This needs no session, so it works while the room
is down. In-flight queries share the existing cap and deadline with the
stream path; a query the direct path cannot take (no configured servers, cap
reached, unparsable) falls through to the stream path as today.

**Logging.** One line at start (`direct rules: 1103 names, 12832 prefixes`),
`direct to host:port (name|prefix|sni|host)` per connection at the same level
the tunnel line has, sniff misses at debug.

### Engine: configuration surfaces

- `mobile.Runtime.SetDirectRules(text string) error` — parses now, so a bad
  list is refused at set time like a bad DNS list; `""` clears. gobind:
  `setDirectRules(_:) throws` in Swift, `setDirectRules(String)` on Android.
- `pkg/olcrtc/client.Config.DirectRules string` — parsed when the client
  runs; a parse error ends the run before anything dials.
- YAML (`internal/config`): a `route` block on `Settings`, overridable per
  failover profile:
  ```yaml
  route:
    direct:            # one rule per entry
      - domain:ru
      - 10.0.0.0/8
    direct_file: rules.txt   # relative to the config file; one rule per line
  ```
  Both may be given; the file's rules follow the inline ones.
  `docs/configuration.md` and `.ru.md` document the block.

### App: iOS

- `OlcrtcDirectRules` (commonMain): the text the engine takes — the
  `XrayConfig.PRIVATE_RANGES`, then both geosite lists, then the geoip list,
  one rule per line. The same lists Xray gets under Bypass Russia on xhttp.
- `IosOlcRtcStartRequest.directRules` (empty under Global) → written into
  `olcrtc.json` by the app → `OlcrtcEngine.Parameters.directRules` →
  `runtime.setDirectRules` before `start`. The tun's resolvers stay OpenDNS
  through the SOCKS port: the engine intercepts every port-53 datagram
  regardless of its destination, so nothing about the tun changes.
- The in-app olcRTC bridge (`SwiftOlcRtcManager`) passes the field too; it
  is the same request type.
- `scripts/typecheck-ios-olcrtc.sh`'s gobind shim learns `setDirectRules`.
- Cores pin: `OLCRTC_VERSION` to the engine commit, `CORES_BUILD` 23 → 24.

### What the user sees

Bypass Russia on, olcRTC room: `yandex.ru/internet` shows the carrier's
address, `whatismyip.com` the room's exit, Russian sites open while the room is
reconnecting. Under Global nothing changes. `olcrtc.log` in the app's export
carries the `direct rules:` line and one `direct to` line per direct
connection.

## Error handling

- Rule text that does not parse: the start fails with the offending line;
  the app shows the engine's message like any other start failure.
- Direct dial failure: logged, the SOCKS client gets a host-unreachable reply
  (or a close when the reply was already sent); never a fallback into the
  tunnel — a rule is a decision, and a silent fallback would hide a broken
  physical path behind a working room.
- Direct DNS with no answer: dropped, the resolver retries (as the stream
  path does); the ring demotes the silent server.
- The direct UDP flow table full: the packet takes the lane (logged at debug).

## Testing

Engine (`go test -race`, `golangci-lint` clean, default and `olcrtc_lean`):

- `internal/route`: syntax, errors with line numbers, suffix and exact
  matching, prefix merging, IPv6 and mapped addresses, empty text.
- `internal/sniff`: SNI from `crypto/tls` ClientHellos (several server names,
  a record split across reads), HTTP/1 Host with and without a port, junk,
  server-first silence.
- `internal/client`: CONNECT by name to a direct target reaches a local TCP
  server without a session; by address in a prefix likewise; by address
  outside the prefixes with a TLS hello naming a direct host goes direct
  with the hello replayed; the same hello for a non-direct name reaches the
  smux CONNECT with the hello replayed after the ack; rules off leaves the
  existing tests as they are. UDP: a datagram to a direct address echoes
  back through the association; DNS: a query for a direct name is answered
  by a `fakedns` on the ring and never opens a stream, a query for another
  name still takes the stream.
- `protect`: `Exchange` reaches the preferred server, moves on after silence,
  reports no servers.
- `mobile`, `pkg/olcrtc/client`, `internal/config`: the new setters and
  fields map through; bad text is refused where documented.

App: `jvmTest` for `OlcrtcDirectRules` (every line parses, the private ranges
and `domain:ru` are present, no blank lines); the Swift bridges typecheck with
the Linux toolchain in both language modes.

Device: the user's phone, the checks listed under "What the user sees".

## Rollout

1. Engine: commit on `proofkit`, push; note the pseudo-version.
2. App: pins + wiring + docs, push `main`.
3. `ios-frameworks.yml` (publish=true) → cores b24; `release.yml`
   (platforms=all, play_track=internal) → 1.0.4xx as a prerelease.
4. olcbox#28: a comment with what shipped and how to check it.
