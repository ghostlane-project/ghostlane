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
#
# 1 -> 2: the same commit with the extension's own lwIP sizes
# (scripts/patches/hev-lwipopts-ios.patch: smaller static pools and a 32 KiB
# window), applied by build-hev-ios.sh.
HEV_BUILD="${HEV_BUILD:-2}"

# The lwIP options patch is part of what the framework is, so the tag carries
# its digest as well: an edit to the patch names a new tag by itself, and
# every consumer keyed on the tag (the destination stamp, the local cache,
# the published release) fetches the new build instead of handing back the
# old one. The patch is stored byte for byte (.gitattributes: -text), so the
# digest is the same on every checkout.
HEV_PATCH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/patches/hev-lwipopts-ios.patch"
if command -v shasum >/dev/null 2>&1; then
    HEV_PATCH_ID="$(shasum -a 256 "${HEV_PATCH}" | cut -c1-8)"
else
    HEV_PATCH_ID="$(sha256sum "${HEV_PATCH}" | cut -c1-8)"
fi
if [ "${#HEV_PATCH_ID}" -ne 8 ]; then
    echo "hev-pins.sh: cannot read ${HEV_PATCH}" >&2
    return 1 2>/dev/null || exit 1
fi

# The tag the workflow publishes to and fetch-hev-ios.sh downloads from. The
# short commit keeps it readable; the build number and the patch's digest keep
# it distinct.
HEV_TAG="hev-socks5-tunnel-${HEV_COMMIT:0:12}-b${HEV_BUILD}-l${HEV_PATCH_ID}"
