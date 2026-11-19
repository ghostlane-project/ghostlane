# shellcheck shell=bash
# The one pin of ghostlane-core, the Rust library the iPhone's packet tunnel
# extension links (xray-rust's xray-ffi and the olcRTC client's olc-ffi, one
# runtime, one allocator, one byte budget). Sourced by
# build-ghostlane-core-ios.sh. Moving the core is a change of this one line
# (the UniFFI Swift is generated from the library at each build).
#
# Now: ghostlane-project/xray-rust 8f8ec411 (gl/olc-diag-1): fa91beb6
# (step B + olc-ffi installing the signalling's TLS) + offers numbering their
# lines 0, 1, 2 (1.0.475: MTS Link refused "can't parse mid 7GO") + a peer's
# end logged at info + ghostlane-core's `boring` feature (1.0.476: VK Calls'
# SFU refused str0m's own DTLS hello with handshake_failure) + the VK, MTS and DTLS diagnostics at info (1.0.477: VK's hello unanswered; 1.0.478: no data channel opened, audio only).
GHOSTLANE_CORE_REPO="${GHOSTLANE_CORE_REPO:-ghostlane-project/xray-rust}"
GHOSTLANE_CORE_COMMIT=8f8ec411749b1ba7ea36e656617f7a56e56d6797
GHOSTLANE_CORE_TOOLCHAIN="${GHOSTLANE_CORE_TOOLCHAIN:-1.96.0}"
