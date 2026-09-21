# Bypass: reading the name out of a QUIC Initial

Ghostlane issue #34. Follows `2026-09-15-olcrtc-client-routing-design.md`, which
gave the client its own rules, and `2026-09-21-bypass-sniff-race-design.md`,
which made the TCP sniff free.

## Problem

Under Bypass the client decides by name when it has one and by address
otherwise. TCP gets a name either way: `internal/sniff` reads the TLS
ClientHello or the HTTP Host out of the first bytes. UDP does not — it is
routed by address, or by the name when the SOCKS client happened to supply one.

So a Russian site behind a CDN goes direct over TCP (the sniff reads the name,
the rules cover it) and through the tunnel over HTTP/3 (the address belongs to
the CDN, which is not in `geoip-ru`). The same page, two paths, and the slower
one is the one a modern browser prefers.

## What it would take

A QUIC Initial carries the ClientHello in CRYPTO frames, encrypted with keys
anyone can derive: RFC 9001 §5.2 gives the initial secret as
HKDF-Extract(salt, DCID) with a version-specific salt, and the packet
protection and header protection keys follow from it by HKDF-Expand-Label.
AES-128-GCM and AES-ECB header protection; `golang.org/x/crypto` is already a
dependency and carries HKDF.

Roughly: strip header protection to learn the packet number length, decrypt the
payload, reassemble CRYPTO frames by offset, parse the ClientHello for SNI.
About 200 lines with the table of salts for v1 and v2, plus coalesced packets
(an Initial may share a datagram with a Handshake) and Retry (a second Initial
with a new DCID, which simply re-derives).

Where it goes: `tryDirectUDP` in `internal/client/direct_udp.go`, on the first
datagram of a flow to an address the rules do not cover. The decision is made
on that one packet — no waiting, because a QUIC client speaks first, which is
the whole difference from the TCP case — and the flow is then pinned either
direct by name through `protect.Dialer` or into the tunnel. The buffer is one
packet; a phone holds nothing else.

## The alternative: not doing it

Worth stating plainly, because it is cheap and nearly right.

DNS for a name the rules cover already goes to the network's own resolver
(`tryDNSDirect`), so the browser gets the CDN address the *local* network would
give it. What it does with that address is then decided by address alone: TCP
gets the name back through the sniff, QUIC does not.

The cost of leaving it is invisible: a Russian CDN-hosted site loads over the
tunnel instead of direct, so it is slower, it crosses a border twice, and it
spends the exit's traffic. Nothing breaks, nobody sees an error, and the user
cannot tell which path a page took.

That invisibility is the argument for doing it. A bypass that quietly does not
apply to the protocol a browser prefers is a bypass that stops meaning what it
says.

## Risk

Getting the parse wrong misroutes traffic. The failure is safe by
construction — anything not recognised as a QUIC Initial with a readable SNI
takes the tunnel, which is what happens today — and the parse never influences
anything but the choice of path.

The keys are public, so nothing here weakens anything: this is the same
computation every middlebox on the path can already do, which is why QUIC v2
exists and why the salts are versioned.

## Testing

Recorded first datagrams, not live traffic: a v1 Initial, a v2 Initial, a
coalesced Initial+Handshake, a Retry followed by its second Initial, a
truncated packet, and a datagram that is not QUIC at all. Each asserts the name
read (or none) and that nothing panics on a malformed input — this parses
hostile bytes, so the table has to include them.

Then the routing test beside `TestSilentClientTakesTheTunnel`: a UDP flow to an
address outside the rules whose Initial names a covered host goes direct, and
one whose Initial names anything else takes the tunnel.

## Rollout

Engine change, no flag, ships with an engine pin. Off by construction where
Bypass is off, because the rules are what the path is compared against.
