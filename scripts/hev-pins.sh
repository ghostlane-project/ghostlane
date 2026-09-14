#!/usr/bin/env bash
# The one version HevSocks5Tunnel.xcframework is built from, in one place.
#
# hev-socks5-tunnel (MIT, C, lwIP) is the tun2socks the tunnel extension puts
# in front of Xray and olcRTC on iOS, in place of sing-box's gVisor stack. It
# is pinned by commit rather than by release because the framework build
# clones the repository with its four submodules; a tag would name the same
# tree, a commit cannot move.
#
# Same discipline as cores-pins.sh: the workflow keeps a copy of these in
# `env:` because a job-level cache key cannot be computed from a sourced shell
# file, and checks the copy against this one.

# heiher/hev-socks5-tunnel main as of 2026-09-06 ("Build: Add android aar build
# with default JNI contract (#329)"), the head when this integration was
# written. Library API: hev_socks5_tunnel_main_from_str (since 2.6.7),
# hev_socks5_tunnel_quit, hev_socks5_tunnel_stats.
HEV_COMMIT="${HEV_COMMIT:-941c758101385d145c66210ac88991daaf27d4b6}"

# Bumped when the framework's shape changes while the commit does not (a slice
# added or removed, different minimum OS versions).
HEV_BUILD="${HEV_BUILD:-1}"

# The tag the workflow publishes to and fetch-hev-ios.sh downloads from. The
# short commit keeps it readable; the build number keeps it distinct.
HEV_TAG="hev-socks5-tunnel-${HEV_COMMIT:0:12}-b${HEV_BUILD}"
