#!/usr/bin/env bash
# The three versions Cores.xcframework is built from, in one place.
#
# They used to live in two: build-cores-ios.sh derived the cache key and the
# release tag from its own copy, while fetch-cores-ios.sh carried the tag as a
# literal — and the literal was never updated when olcRTC was re-pinned. The two
# then disagreed silently, and the failure mode was the worst kind: fetch looks
# in the destination first, so as long as a freshly built framework was sitting
# there the right one got used and nothing looked wrong. The moment Gradle swept
# `sharedUI/build` — which it does, that directory is fetchCoresIosXcframework's
# declared output — fetch would fall through to a cache entry named after the
# *previous* olcRTC revision and restore a framework with no UDP in it, while
# every pin in the tree said UDP was there.
#
# Sourced by build-cores-ios.sh and fetch-cores-ios.sh. Bump a version here and
# the cache key, the release tag and the lookup all move together.
#
# The workflow keeps its own copy in `env:` because a job-level cache key cannot
# be computed from a sourced shell file; it checks itself against this one.

# The toolchain is a pin too, and the one that was missing. The workflow said
# `stable`, which was 1.26.5 in August and 1.27.1 by September; the September
# build linked every slice with `golang.org/x/net/http2.(*Transport).connPool`
# left *undefined* — a reference the Go linker handed to the external linker,
# which Xcode then could not satisfy — while the August build defines it, and
# nothing else about the two differed: same three pins, byte-identical headers.
# Exact rather than a floor, for the same reason the modules are: the bind is a
# 20-minute step on a 10x runner and a "works on 1.26.x" claim is only as good
# as the x it was checked on.
GO_VERSION="${GO_VERSION:-1.26.5}"
SINGBOX_VERSION="${SINGBOX_VERSION:-1.13.14}"
LIBXRAY_VERSION="${LIBXRAY_VERSION:-v1.260711.0}"
# Branch `proofkit` of the fork, re-laid on upstream master 189d16c. The lineage
# before it, `proofkit-udp-spike` (archived), was the only one carrying a UDP
# relay; the pin before that was upstream `42ae4e0c`, where
# internal/client/udp.go does not exist at all, so the SOCKS5 server could not
# answer UDP ASSOCIATE and every datagram died inside the extension.
#
# c83717e7e900 → 240487450763: the dual-stack dial fix (olcrtc#1). Carrier auth
# resolved through a single IPv4 literal and dialed whichever family survived
# that, so on a network without IPv4 it failed before any media was negotiated.
# App Review runs on IPv6-only NAT64, which is why this one blocks a submission
# rather than merely one carrier.
#
# 240487450763 → 4d9a1b3554c1: a dial that found no route is retried (olcrtc
# fix/retry-unreachable). EHOSTUNREACH and ENETUNREACH were the one dial error
# the carrier-auth request gave up on after a single try, and on a mobile
# carrier mid-handover — or on a socket pinned to an interface with nothing
# behind it — that single try was the whole attempt. Seen live on one carrier
# as "no route to host" on both families.
#
# 4d9a1b3554c1 → 8264c6cc098d: carrier names resolve over the protected sockets
# (olcrtc fix/mobile-resolver). On a phone the resolver was the system one,
# which inside our own tunnel is the tunnel's DNS, unserved until the cores
# are up - a self-hosted Jitsi host, never in the phone's cache, could not be
# resolved at all (olcbox#13).
#
# 8264c6cc098d → eb730c6e5cfa: a resolver that stays silent is demoted and the next
# public operator is asked (olcrtc fix/mobile-resolver, second commit) - for
# the carriers that blackhole 1.1.1.1 rather than merely refuse it.
#
# eb730c6e5cfa → aad0adc9a6ab: the Jitsi carrier's signalling library dials with
# http.DefaultClient, so its sockets were neither protected nor resolved
# through the configured server; the default transport now goes through the
# protected dialer (olcbox#13, #15). Also srv.sh installs the fork (#14).
#
# aad0adc9a6ab → 74d37f8bc3bf: the host's resolver is asked when no configured
# server answers, on a four-second budget. The build before this one replaced
# the carrier's resolver rather than preceding it, and a network that drops
# every public resolver then answered nothing at all (olcbox#15, second round).
#
# 47646a95afb3 → 9da2735b06c4: the fork rebased onto upstream 189d16c (record
# layer v2, handshake v3, per-session resolver, the mobile.Runtime API). Not
# wire-compatible with the build before it: a room served by the old engine
# answers this one with a handshake timeout, which the app now names.
#
# 9da2735b06c4 → 333b10f0e3c5: two load failures, both of which showed up as a
# session that came up fine and then died during a speedtest (olcbox#15). Every
# lane — data, control, datagram — was numbered from one counter and checked
# against one replay window, so an idle control record numbered far ahead aged
# a whole data backlog out as "record too old"; and liveness counted a pong
# queued behind megabytes of the user's own traffic as a missed pong, four of
# which tore the session down. Both ends interoperate with the build before it.
#
# 333b10f0e3c5 -> 7f913e8e6e84: receive windows a phone can afford. With the
# two faults above fixed an iPhone finally moved a speed test's traffic, and
# the extension was then killed for exceeding its ~50 MB ceiling: smux had a
# 32 MB session buffer and vp8channel two KCP sessions at ~5.7 MB per
# direction. Measured on the same load, anonymous memory went from 48.8 MB
# peak to 36.8 MB with every transfer still completing.
#
# 7f913e8e6e84 -> 74cc79c363e3: an iPhone's own packet tunnel is utun6, and the
# interface prefixes kept out of ICE gathering were tun/ppp/pptp — so the engine
# gathered a candidate on the tunnel it was carrying and tried every STUN and
# TURN server from 172.19.0.1, the tun's own address. Dozens of "can't assign
# requested address" per connection, out of the eight seconds a start is given.
#
# 74cc79c363e3 -> 5e199b54ed8f: "cannot join any room". A GET built with
# http.NoBody has a non-nil body and no GetBody, so the retry guard called it
# unreplayable and refused a second attempt — and then reported that instead of
# what the transport had said, hiding the real failure. An iPhone moving
# between cellular interfaces made the first attempt fail routinely.
#
# 5e199b54ed8f -> e1c4890c189e: MobileSetMemoryLimit, so the extension can give
# the Go runtime a ceiling. A phone's trace caught the kill: 35.1 MB one sample,
# 46.0 MB the next, 250 ms later — the heap doubling between collections rather
# than climbing. Not olcRTC's appetite either: the same phone on Hysteria2, with
# olcRTC never started, sat at 34.8 MB peaking 39.2 MB. One runtime carries all
# three engines, so one limit covers them.
#
# b1e319e1d2bc -> 43a3492f63a8: the server's next session no longer vouches for
# the one it closed (liveness counts payload on our own streams, three excused
# probes instead of eighteen; olcbox#25), a half-open connection that has gone
# silent is closed after a minute instead of kept for the session, and the
# goroutines are named once a minute (olcbox#26); handshake frames are one
# Write each; NACK responder 256; and the olcrtc_lean tag this build now
# passes, which leaves videochannel and livekit out of the bind.
#
# 43a3492f63a8 -> 9336ee9e1def: a port-53 datagram is answered over a smux
# stream (TCP DNS to the resolver the tun advertises) instead of the relay's
# datagram lane, which is what lets hev-socks5-tunnel front olcRTC on iOS;
# and 512-segment KCP windows on the phone profile.
OLCRTC_VERSION="${OLCRTC_VERSION:-v0.0.0-20260914055859-9336ee9e1def}"

# Bumped when the framework's *shape* changes while its pins do not — adding the
# macOS slice being the first case. The versions alone cannot express that: they
# are identical before and after, so the tag would be identical too, and every
# consumer keyed on it — the destination stamp, the local cache, the published
# release — would hand back an iOS-only framework as though it were the one this
# build asked for. That is the same failure the header above describes, arriving
# by a different door.
#
# 2 → 3: the simulator slice. Same three versions, a framework one platform
# wider, and every consumer keyed on the tag — the destination stamp, the local
# cache, the published release — would otherwise hand back the two-slice build
# for a project that now asks for three.
#
# 3 → 4: olcRTC moved, so the tag moves with it either way. Bumped anyway, so
# the tag says out loud that this framework is a different build and not a
# re-publish of the same shape under a longer name.
#
# 4 → 5: olcRTC moved again (the no-route retry), for the same reason.
#
# 5 → 6: same three pins, different toolchain. Build 5 was made by Go 1.27.1
# and does not link (see GO_VERSION above); every consumer keyed on the tag —
# a laptop's destination stamp, its cache, the published release — would keep
# handing that one back if the rebuild wore the same name.
#
# 6 → 7: olcRTC moved again (the resolver fix), for the same reason as 4 → 5.
#
# 7 → 8: olcRTC moved again (the resolver fallback); build 7 was cancelled
# before it published, so nothing wears that tag.
#
# 8 → 9: olcRTC moved again (the Jitsi signalling dial), for the same reason.
#
# 9 → 10: olcRTC moved again (the system-resolver fallback), for the same reason.
#
# 12 → 13: olcRTC moved and its API changed shape (mobile.Runtime instead of
# package functions); every bridge in the app was rewritten for it.
#
# 19 → 20: the same three pins, a different Xray: scripts/patches/
# xray-core-h2-window.patch bounds the xhttp client's HTTP/2 receive windows,
# and the wrapper package exports CoresSetenv, which is how the extension
# hands the values to Go (a C setenv is invisible to it). Bind list changed.
CORES_BUILD="${CORES_BUILD:-21}"

# The revision rather than the whole pseudo-version: the tag stays readable and
# still changes whenever olcRTC does.
#
# The `ios-` prefix is now a misnomer — the framework carries a macOS slice too —
# but it is an identifier, not a description. Renaming it would orphan every
# published release and cache entry to buy nothing.
CORES_TAG="ios-cores-sb${SINGBOX_VERSION}-lx${LIBXRAY_VERSION}-rtc${OLCRTC_VERSION##*-}-b${CORES_BUILD}"
