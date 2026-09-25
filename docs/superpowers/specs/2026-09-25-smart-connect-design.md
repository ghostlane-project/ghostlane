# Smart connect: another transport when one is blocked, olcRTC last

**Date:** 2026-09-25
**Status:** Approved direction (roadmap item 5). Android and desktop; iOS later, after App Review.
**Builds on:** `proofkit-dvpn/docs/superpowers/specs/2026-07-24-olcbox-transport-autofallback-design.md` (grouping,
ordering, probe-before-tunnel, never refuse to connect). This document records what changed since and why.

## Problem

A subscription lists one exit several times, once per transport (`US via RU | …`, `… · Hysteria2`, `… · XHTTP`),
and olcRTC per country (`DE · olcRTC`, `DE · olcRTC · WB`, `DE · olcRTC · SJ`). Which one survives the user's
network changes with the network and over time: Russian DPI freezes a TCP connection to a foreign address after
about 16-20 KB, and mobile "whitelist" shutdowns drop everything but whitelisted services, where only olcRTC's
video-call carriers get through. Today the user finds the working row by hand. `TransportGroup` and
`TransportSelector` group and order the rows but nothing calls them, and there is no probe.

## Decisions

- **olcRTC of the same country is the last step** (owner's decision 2026-09-25, reversing the July exclusion).
  The July concern was a silent move to another country and price. olcRTC now runs on every origin, and an olcRTC
  line is named by its country, so "the same exit" for olcRTC is the same country code, in the same subscription.
  No olcRTC line for that country means no olcRTC step: the country never changes by itself.
- **Probe past the freeze.** A probe that sends one small request passes exactly where the 16 KB freeze strikes.
  A core candidate passes only when a `HEAD https://www.gstatic.com/generate_204` answers 204 **and** a 64 KB
  download (`https://speed.cloudflare.com/__down?bytes=65536`) arrives whole through the same local SOCKS port,
  within 10 s.
- **Whitelist mode goes to olcRTC first.** When the group has an olcRTC step and the VPN is not up, the app asks
  two addresses directly, outside any tunnel, in parallel: a foreign one (`generate_204`) and a domestic one
  (`https://ya.ru/`). Foreign failing while domestic answers is whitelist mode: olcRTC steps come first, and the
  core candidates still follow, since the check can be wrong.
- **olcRTC is not probed.** Joining a room takes 5-30 s and the connection itself is the test (the tunnel check
  that already runs after every connect). An olcRTC step is chosen and connected.
- **Probe with the core alone, never with the tunnel** (unchanged): the candidate's core on a kernel-assigned
  loopback port with a random per-probe login (see `SocksLogin`), killed in a `finally`. On Android its work dir is
  its own (`AndroidCoreProcess` label `probe-…`), so the service's core is never touched.
- **Order:** the group's last winner, then the row the user picked, then Reality → Hysteria2 → gRPC → XHTTP → TLS,
  then olcRTC in the order the subscription lists it (Telemost, WB, SaluteJazz). In whitelist mode olcRTC moves to
  the front. An olcRTC winner leads only in whitelist mode: one fallback to the slowest path must not keep the
  user there once the network lets the cores through again.
- **Core probes run one at a time, in the plan's order, and stop at the first that passes.** The process-wide
  SOCKS authenticator (`withProxyAuthentication`) answers one login on one port at a time, so parallel probes
  would queue on it anyway. The common case is one probe (a few seconds); every core failing costs about 10 s
  per core, and a second tap cancels, as it cancels Lowest.
- **Never refuse to connect** (unchanged): every probe failing leaves the user's own choice active and connects it.
- **Switch:** "Smart connect" in subscription settings, default on. Off means today's behaviour exactly.
- **Scope of v1:** the explicit Connect path. When Lowest is on for the subscription it keeps its own flow.

## Architecture

1. `net/TransportGroup.kt` (pure): `countryOf(name)` — the leading ISO country code of a core line's base name
   or an olcRTC line's name, null when there is none; `olcrtcFallbacks(entry, all)`.
2. `net/SmartConnect.kt` (pure): `plan(active, all, lastKnownGood, whitelist)` → ordered steps
   (`Probe(entry)` for cores, `Connect(entry)` for olcRTC); `groupKey(active)` for the last-known-good map.
3. `net/TransportCheck.kt` (common, takes an `HttpClient` so it is testable): `passes(client)` — the 204 and the
   64 KB download; `WhitelistCheck.detect(client)`.
4. `VpnManager.probeTransport(location): Boolean?` — null where the platform cannot probe (iOS). The probe itself
   is shared by Android and the desktop (`jvmAndroidMain` `TransportProbe`: free loopback port, random login,
   wait for the port, `TransportCheck` under `withProxyAuthentication`, stop in a `finally`); each platform only
   says how to start a core.
5. `HomeScreenModel`: before `startVpn()` on the explicit path, run the plan in `selectionJob` (so a second tap
   cancels it, as it cancels Lowest), set the winner active, remember it for the group, connect.
6. `SubscriptionSettings`: `smartConnect: Boolean = true`, `lastKnownGoodTransport: Map<String, String>`.

## Testing

- Unit: country parsing on the real names; plan ordering (last-known-good core and olcRTC, whitelist mode, no olcRTC
  for another country, no olcRTC at all); the check's 204 + 64 KB rule and the whitelist rule (MockEngine); the
  orchestration picks the first passing candidate, falls back to olcRTC, and connects the user's choice when all fail.
- Device: a network where Reality freezes (Russian mobile) — the app ends on XHTTP or olcRTC by itself; a whitelist
  shutdown — straight to olcRTC.

## Out of scope

Mid-session fallback (a drop keeps today's reconnect), Lowest integration, iOS, other countries' olcRTC.
