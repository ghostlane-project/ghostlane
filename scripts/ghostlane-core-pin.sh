# shellcheck shell=bash
# The one pin of ghostlane-core, the Rust library the iPhone's packet tunnel
# extension links (xray-rust's xray-ffi and the olcRTC client's olc-ffi, one
# runtime, one allocator, one byte budget). Sourced by
# build-ghostlane-core-ios.sh. Moving the core is a change of this one line
# (the UniFFI Swift is generated from the library at each build).
#
# Now: ghostlane-project/xray-rust d520064f, the olc integration's (step B)
# candidate gl/olc-integ-2, published as gl/olc-integ-lab-8: ghostlane
# 77f14eb8 + olc/ (carriers, olc-ffi with its providers, olc-rtc, olc-p2p,
# olc-kcp) + ghostlane-runtime + ghostlane-core + gl/tun-bridge-memory. Once
# the fork's ghostlane is fast-forwarded to it, it is a commit on ghostlane.
GHOSTLANE_CORE_REPO="${GHOSTLANE_CORE_REPO:-ghostlane-project/xray-rust}"
GHOSTLANE_CORE_COMMIT="${GHOSTLANE_CORE_COMMIT:-d520064fbd420f99df82fb1b2c30fbac3bc32811}"
GHOSTLANE_CORE_TOOLCHAIN="${GHOSTLANE_CORE_TOOLCHAIN:-1.96.0}"
