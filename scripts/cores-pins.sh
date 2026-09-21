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
# Two of the pins now reach past the Cores: OLCRTC_VERSION is the engine of every
# platform and GO_VERSION the toolchain every build checks itself against.
# scripts/olcrtc-pin.sh reads them, with both variables unset first so an
# inherited value cannot win, for release.yml, pr-checks.yml and the release gate.
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
# as the x it was checked on. release.yml carries a copy in its env; its
# release_version job fails when the copy and this line disagree.
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
#
# 9336ee9e1def -> d59a979359dd: a 16 KB read buffer per SOCKS UDP association
# on the phone profile instead of 64 KB, and one idle-flow sweeper for all
# associations instead of one goroutine each - the resolver behind a
# tun2socks opens an association per query, 117 of them under a speed test.
#
# d59a979359dd -> 850aa5f90a8d (olcbox#22, both halves): the client sends
# its hello again every 4 s while the handshake timeout runs, because the
# Jitsi videobridge drops a relayed message to an endpoint whose channel is
# not open yet and a server that comes up after the phone lost the only
# hello there was; and the livekit engine is back in the lean bind - the
# wbstream provider is served by it, and without it every WB Stream room
# failed with "engine not found". That is about 3 MB of heap at start and
# 20 MB of binary the lean tag had been saving.
#
# 850aa5f90a8d -> b9dc3a192e34 (olcbox#28): the client carries Bypass Russia
# itself. With hev in front there is no router on the olcRTC path, so the
# engine takes the app's lists as direct rules: a matching name or address
# is dialed from the engine over the pinned interface (a name the tun hides
# behind an address is read off the TLS hello or the HTTP Host), a matching
# name's DNS query goes to the network's own resolver, matching UDP is
# relayed by the engine, and everything else rides the room. New API:
# MobileRuntime.setDirectRules.
#
# aaffe1e05c3b (2026-09-16): at most 64 SOCKS requests wait on a missing
# session, the rest are refused at once, and a reconnect waits for a route
# (one socket the pin accepts) before it opens a hundred — the network-loss
# death of olcbox#37. No API change.
#
# aaffe1e05c3b -> 653bb167b496 (2026-09-18): Jitsi fetches config.js like a
# browser and falls back to meet.jitsi on host-unknown (ghostlane#22: a docker
# Jitsi whose config.js did not load refused the XMPP stream); LiveKit, which
# serves WB Stream, joins on a 25 s budget instead of the SDK's 5 s (ghostlane#38:
# WB Stream on cellular); and the release gate (internal/gate), which the app's
# gate needs in the pinned engine. No API change.
#
# 7142c4a04b8b -> 1afdb706d5f9 (olcrtc#40, ghostlane#51): a location can name
# several rooms and the engine walks them. A server whose rooms live about a day
# advertises the next one in the subscription under the location's line; the
# supervisor now takes a list, advances when the room it is in ends, re-reads
# the list at every hop - so the host can grow it while a session is live - and
# reports every opened session to the host, which is how the iOS extension
# learns a handover happened while the app is suspended. New API:
# MobileRuntime.addFailoverRoom, clearFailoverRooms and setSessionListener.
# From the same wave, without an API change: a room is given up only while
# there is another one to try, a session ends on a graceful close instead of
# waiting for liveness to notice, and it takes a run of refusals - not one - to
# call an exit IPv6-less.
#
# 1afdb706d5f9 -> b1dfbacc8df0: a client that stops tells the server instead
# of leaving it to liveness (#23); a Jitsi client addresses the server
# rather than the whole room, which is what was filling every other client's
# buffer on the bridge (#25); a detached session's peers are closed together
# and a room that moves during a reconnect is no longer dropped (#31); and
# under Bypass a CONNECT to an address the rules do not cover no longer
# waits out the sniff window before anything is dialed, which every SSH,
# SMTP, IMAP and database connection was paying (ghostlane#35). Same API.
#
# Since the release gate (docs/release-gate.md) this line is the engine of every
# platform, not the Cores' alone. release_version resolves it to the full commit
# (scripts/olcrtc-pin.sh: the 12-hex tail must exist in ghostlane-project/olcrtc
# and the time in the pseudo-version must be that commit's), the gate tests that
# commit, and then Windows, macOS, Linux, Android and the iOS app's OlcRtcMobile
# check it out while the extension's Cores fetch it through the module proxy.
# Android and the desktop used to follow the tip of `proofkit` instead, so an
# engine fix now reaches users only through a re-pin, in this order:
#   1. dispatch "Release gate" (gate.yml) with engine_ref=<the candidate commit>;
#   2. green: set this to v0.0.0-<commit time, UTC, yyyymmddhhmmss>-<first 12
#      hex>, bump CORES_BUILD below, and copy the value into
#      .github/workflows/ios-frameworks.yml's env;
#   3. run "iOS Frameworks" so the Cores for the new CORES_TAG exist;
#   4. push (the push runs the gate against the new pin), then release.
# The pin must carry internal/gate. aaffe1e05c3b predates it and the gate refuses
# it, so until the next re-pin a release passes only with gate: skip. Pin commits
# on `proofkit`: GitHub drops a commit no branch reaches, and every checkout of
# it then fails.
OLCRTC_VERSION="${OLCRTC_VERSION:-v0.0.0-20260921104921-b1dfbacc8df0}"

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
#
# 22 → 23: the engine pin above (850aa5f90a8d); the lean bind links the
# livekit engine again, so the framework grows by about 20 MB.
#
# 23 → 24: the engine pin above (b9dc3a192e34); its API grew setDirectRules,
# which the extension now calls on every start.
#
# 24 → 25: the engine pin above (aaffe1e05c3b), olcbox#37; same API.
# 25 → 26: the engine pin above (653bb167b496): the Jitsi config.js fallback, the
# LiveKit join budget and the release gate; same API.
# 26 → 27: the engine pin above (7142c4a04b8b): the release gate's whole wave -
# transport-cc to Jitsi, the relay window, the datachannel batching, the
# seichannel window, the WB publish ceiling and the reconnect rewrite; same API.
# 27 -> 28: the engine pin above (1afdb706d5f9), whose API grew the failover
# room list and the session listener; the extension's RoomKeeper calls both.
# 28 -> 29: the engine pin above (b1dfbacc8df0): a stop that tells the peer,
# a Jitsi client that addresses the server rather than the room, a teardown
# that closes its peers together and keeps the reconnect it was asked for,
# and a Bypass sniff that no longer delays a silent connection; same API.
CORES_BUILD="${CORES_BUILD:-29}"

# The revision rather than the whole pseudo-version: the tag stays readable and
# still changes whenever olcRTC does.
#
# The `ios-` prefix is now a misnomer — the framework carries a macOS slice too —
# but it is an identifier, not a description. Renaming it would orphan every
# published release and cache entry to buy nothing.
CORES_TAG="ios-cores-sb${SINGBOX_VERSION}-lx${LIBXRAY_VERSION}-rtc${OLCRTC_VERSION##*-}-b${CORES_BUILD}"
