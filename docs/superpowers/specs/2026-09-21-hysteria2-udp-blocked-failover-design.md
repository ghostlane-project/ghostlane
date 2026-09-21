# When the carrier kills UDP, Hysteria2 stops carrying and nobody says so

Ghostlane issue #27.

## Problem

Two exports from one iPhone, one Hysteria2 location, one carrier, ninety
minutes apart:

- 05:32 UTC — 103 QUIC streams under a speed test, extension at 40 MB, test
  passes.
- 07:06 UTC — the extension is alive (23 MB, 65 goroutines) and has **no** QUIC
  streams at all: 18 `hysteria2.(*udpPacketConn).WaitReadPacket` and 17
  `udpnat2.(*natConn).ReadPacket`, i.e. UDP sessions waiting for packets that
  never come. Nothing loads.

Same server, same build, same device. The difference is the carrier: UDP to
that address is being killed selectively, which is ordinary for Russian mobile
networks.

The app cannot fix the carrier. What it must not do is leave the user looking
at a tunnel that says Connected and carries nothing.

## Goal

A Hysteria2 tunnel that is not carrying is noticed within seconds, said out
loud, and — when the same subscription offers a TCP-based transport — replaced
by it without the user having to guess.

## Non-goals

- Detecting *why* UDP died, or working around it. QUIC over a blocked path is
  not ours to fix.
- Switching back automatically. A carrier that blocked UDP this hour is not
  evidence about the next connection; the next deliberate connect starts from
  the user's own choice again.
- Doing any of this on Android or desktop in this change. The same detector
  fits, but the evidence and the reports are from iOS.

## Design

**The detector is the probe we already have.** `TunnelVerifier` reaches
`https://1.1.1.1/cdn-cgi/trace`, falls back to `https://cloudflare.com/...`
when one address is blackholed, and parses the exit out of the answer. On iOS
the tunnel is the system's, so the app's own request already rides it: the
probe needs no SOCKS proxy, only a variant of `verify` that builds a plain
client. That variant is the whole new surface in `commonMain`.

**When it runs.** `IosVpnManager` already watches its own status. On the
transition into `Connected` for a location whose kind is `Hysteria2`, it runs
the probe once with an 8 s budget, and if that fails, once more. Two failures
is the verdict: a tunnel the system calls up, twice unable to carry a few
hundred bytes to either of two well-known endpoints.

**What it does with the verdict.**

1. Writes it plainly: `hy2 carried nothing for 16 s (udp likely blocked)`.
2. Looks for a sibling: a complete location from the **same subscription URL**
   whose kind is `Vless` — Reality or XHTTP, both of which ride TCP.
3. If there is one, says which (`switching to <name>`), makes it active and
   reconnects. If there is none, says that instead, and leaves the tunnel up:
   an unhelpful tunnel the user can see is better than one the app tore down
   for them.
4. Marks the Hysteria2 location in the list — "UDP blocked by the carrier" —
   so the next manual choice is informed.

**What keeps it from fighting the user.** The switch happens at most once per
deliberate connect: a flag set when the user starts a tunnel and cleared by the
switch. A reconnect the app itself started does not re-arm it. Anything the
user does by hand — another location, Stop — clears it too.

**Why not a goroutine counter.** The evidence in the issue is a goroutine
breakdown, and sing-box does know its QUIC stream count. But reading it means a
new bridge call from the extension into the app for a signal that only tells us
what the probe already tells us, and the probe is honest about the thing that
matters: whether bytes get out.

## Error handling

- The probe fails because the phone has no network at all: the switch still
  fires, the TCP sibling also fails, and the app stops. One wasted reconnect,
  no wrong state.
- The subscription has one location: nothing to switch to, and the log says so.
- The user switches location while the probe is in flight: the generation
  counter `IosVpnManager` already keeps makes the verdict stale and it is
  dropped.

## Testing

- `TunnelVerifier`: the proxy-less variant answers from a local endpoint and
  returns null when both URLs fail. (`commonTest`, no network.)
- The sibling search: same subscription, complete, `Vless`, not the location
  that failed — and null when the subscription holds only Hysteria2.
- The once-per-connect flag: a second verdict in the same session does not
  switch again.

## Rollout

iOS only, no flag. The line in the log and the label in the list are the whole
user-visible surface.
