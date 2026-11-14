#!/usr/bin/env bash
# The build tags of the sing-box binaries Ghostlane ships, in one place:
# Android's libsingboxcore.so (release.yml, through the NDK) and the desktops'
# sing-box (scripts/build-singbox.sh, which release.yml runs on Windows, macOS
# and Linux). PR Checks builds the same lists and checks every config the app
# writes with them. The iPhone's Cores are not built from here:
# build-cores-ios.sh keeps its own list, for a library in a 50 MB extension.
#
# Upstream's release/DEFAULT_BUILD_TAGS_OTHERS is no longer read. That list is
# for everybody who runs sing-box, and it carried Tailscale, WireGuard, ACME,
# CCM and OCM into binaries that never use them. Every config our sing-box runs
# is written by sharedUI/.../net/SingBoxConfig.kt: no screen takes a sing-box
# config from the user (a router export is run by the router's own sing-box).
# So a tag is here because a builder needs it, and the line above it says which.
#
# A builder that starts to need another tag adds it here, in the same change:
# the binary PR Checks builds from this file then accepts the new shape. Left
# out, `sing-box check` fails there instead of the app failing at connect. A
# new SINGBOX_VERSION adds nothing here on its own: read its include/ and
# release/DEFAULT_BUILD_TAGS_OTHERS against these lists when the pin moves.
#
# Not in cores-pins.sh, because that file is in the iPhone Cores' cache key
# (ios-frameworks.yml): a change to these lists would rebuild a framework that
# does not read them.
#
# Dropped from upstream's list, and why nothing misses them:
#   with_tailscale      the Tailscale endpoint; no config has one. Likely the
#                       largest of them: Tailscale's whole client comes with it.
#   with_wireguard      the WireGuard endpoint; no link we parse is WireGuard.
#   with_acme           certificates for a TLS *inbound*; ours are SOCKS, on
#                       loopback or the LAN.
#   with_ccm, with_ocm  the Claude Code and OpenAI Codex multiplexer services.
# Never in upstream's list either: with_naive_outbound (cronet, cgo), with_grpc
# (our gRPC transport is the built-in lite one), with_v2ray_api.

# Android: hev-socks5-tunnel owns the tun and hands sing-box SOCKS
# connections (OlcboxVpnService), so sing-box runs only the SOCKS shapes:
# SingBoxConfig.build and buildSocksChain.
#
#   with_quic             the Hysteria2 outbound is QUIC.
#   with_utls             Reality (the vless `reality` block is a uTLS client,
#                         and our Reality hello patch is in it) and the `utls`
#                         fingerprint every TLS outbound is written with.
#   badlinkname           not a feature: upstream compiles its TLS record path
#                         (common/badtls, read waiting over uTLS too) and kTLS
#                         with it, linked into crypto/tls; release/LDFLAGS'
#                         -checklinkname=0, which every build still reads, is
#                         what lets those links through. Kept so the TLS code
#                         is the code upstream ships.
#   tfogo_checklinkname0  the same flag for tfo-go (TCP Fast Open dials on
#                         Windows). Kept with it, for the same reason.
SINGBOX_TAGS_ANDROID="with_quic,with_utls,badlinkname,tfogo_checklinkname0"

# The desktops: Android's, and what a sing-box that owns the tun needs. That is
# Windows (DesktopVpnManager) and the macOS tunnel helper (MacOsTunController,
# desktopApp/tunneldaemon), both from SingBoxConfig.buildDesktopTun. Linux puts
# hev in front and needs only Android's tags, but one desktop list keeps the
# binary PR Checks builds on Linux the same as the two that run a tun.
#
#   with_gvisor     the tun inbound's `stack: gvisor`. Without it sing-box
#                   refuses the tun ("gVisor is not included in this build").
#   with_clash_api  `experimental.clash_api`, the local API the traffic chart
#                   reads (vpn/desktop/TunnelTraffic.kt,
#                   scripts/check-singbox-traffic-api.sh).
#   with_dhcp       for macOS. A `local` resolver in a process with a tun asks
#                   the physical network's DHCP resolvers on Darwin
#                   (dns/transport/local/local_darwin.go); without the tag it
#                   asks the system, which the helper points at the tun
#                   itself (SingBoxConfig.DESKTOP_TUN_RESOLVER). Off Darwin
#                   it only adds the `dhcp` server type, which nothing writes.
SINGBOX_TAGS_DESKTOP="with_gvisor,with_quic,with_utls,with_clash_api,with_dhcp,badlinkname,tfogo_checklinkname0"
