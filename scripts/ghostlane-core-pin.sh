# shellcheck shell=bash
# The one pin of ghostlane-core, the Rust library the iPhone's packet tunnel
# extension links (xray-rust's xray-ffi and the olcRTC client's olc-ffi, one
# runtime, one allocator, one byte budget). Sourced by
# build-ghostlane-core-ios.sh. Moving the core is a change of this one line
# (the UniFFI Swift is generated from the library at each build).
#
# Now: ghostlane-project/xray-rust 2bce8cfc (gl/olc-mid-lab-1): fa91beb6 (step B
# + olc-ffi installing the signalling's TLS) + offers numbering their lines
# 0, 1, 2 (1.0.475 on an iPhone: MTS Link refused "can't parse mid 7GO") and
# a peer's end and VK's renegotiation failures logged at info.
GHOSTLANE_CORE_REPO="${GHOSTLANE_CORE_REPO:-ghostlane-project/xray-rust}"
GHOSTLANE_CORE_COMMIT="${GHOSTLANE_CORE_COMMIT:-2bce8cfccbfa8e1240de9680b46362b594bede1a}"
GHOSTLANE_CORE_TOOLCHAIN="${GHOSTLANE_CORE_TOOLCHAIN:-1.96.0}"
